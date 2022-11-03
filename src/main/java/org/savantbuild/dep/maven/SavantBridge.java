/*
 * Copyright (c) 2014-2020, Inversoft Inc., All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.savantbuild.dep.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.savantbuild.dep.DefaultDependencyService;
import org.savantbuild.dep.domain.ArtifactID;
import org.savantbuild.dep.domain.ArtifactMetaData;
import org.savantbuild.dep.domain.License;
import org.savantbuild.dep.domain.Publication;
import org.savantbuild.dep.domain.ReifiedArtifact;
import org.savantbuild.dep.workflow.PublishWorkflow;
import org.savantbuild.dep.workflow.process.CacheProcess;
import org.savantbuild.dep.xml.ArtifactTools;
import org.savantbuild.domain.Version;
import org.savantbuild.net.NetTools;
import org.savantbuild.output.Output;
import org.savantbuild.output.SystemOutOutput;
import org.savantbuild.security.MD5;

/**
 * The bridge between Maven artifacts and Savant artifacts.
 *
 * @author Brian Pontarelli
 */
public class SavantBridge {
  /**
   * A stricter regex version of our Version parser.
   */
  private static final Pattern semanticVersion = Pattern.compile(
      "^(?:0|[1-9]\\d*)" + // Major (Required)
          "(?:\\.(?:0|[1-9]\\d*))?" + // Minor (Optional, defaults to 0 later)
          "(?:\\.(?:0|[1-9]\\d*))?" + // Patch (Optional, defaults to 0 later)
          "(?:-(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*)" + // Prerelease info (Permits dot or dash separated alpha numeric character segments https://semver.org/#spec-item-10)
          "?(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$"); // Metadata info (Similar to the prerelease except a little more flexible)

  private final CacheProcess cacheProcess;

  private final boolean debug;

  private final GroupMappings groupMappings;

  private final BufferedReader input;

  private final Map<String, List<License>> licenseMappings = new HashMap<>();

  private final PublishWorkflow publishWorkflow;

  private final DefaultDependencyService service;

  private boolean includeOptionalDependencies;

  private boolean includeTestDependencies;

  public SavantBridge(Path directory, GroupMappings groupMappings, boolean debug) {
    this.input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    this.groupMappings = groupMappings;

    Output output = new SystemOutOutput(true);
    this.service = new DefaultDependencyService(output);

    // Cache only workflow that will check if the artifact coming from Maven already exists in our repository
    this.cacheProcess = new CacheProcess(output, directory.toString());
    this.publishWorkflow = new PublishWorkflow(this.cacheProcess);
    this.debug = debug;
  }

  public void run() {
    System.out.println("Prompt Enabled [" + prompt() + "]");

    includeTestDependencies = ask("Include test dependencies?", "n", "Invalid Response", (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n"))).equals("y");
    includeOptionalDependencies = ask("Include optional dependencies?", "n", "Invalid Response", (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n"))).equals("y");

    MavenDependency mavenDependency = new MavenDependency();
    mavenDependency.group = ask("Maven group (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenDependency.id = ask("Maven artifact id (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenDependency.version = ask("Maven artifact version (i.e. 3.0.GA.1)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);

    buildMavenGraph(mavenDependency, new HashSet<>(), new HashSet<>());
    downloadAndProcess(mavenDependency);
  }

  private String ask(String message, String defaultValue, String errorMessage, Predicate<String> predicate) {
    String answer;
    boolean valid;
    do {
      System.out.printf(message + (defaultValue != null ? " [" + defaultValue + "]" : "") + "?\n");
      try {
        answer = input.readLine();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      if (StringUtils.isBlank(answer) && defaultValue != null) {
        return defaultValue;
      }

      if (predicate != null) {
        valid = predicate.test(answer);
        if (!valid) {
          System.out.printf(errorMessage);
        }
      } else {
        valid = true;
      }
    } while (!valid);

    return answer;
  }

  /**
   * Recursively populates the Maven dependency graph.
   *
   * @param mavenDependency The maven artifact to fetch the graph for.
   */
  private void buildMavenGraph(MavenDependency mavenDependency, Set<MavenDependency> cycleCheck, Set<MavenDependency> visitedArtifacts) {
    if (cycleCheck.contains(mavenDependency)) {
      throw new RuntimeException("The Maven artifact you are trying to convert contains a cycle in its dependencies. The cycle is for the artifact [" + mavenDependency + "]. Cycles are impossible in the real world, so it seems as though someone has jimmied the POM.");
    }

    MavenDependency existing = visitedArtifacts.stream().filter(mavenDependency::equals).findFirst().orElse(null);
    if (existing != null) {
      mavenDependency.savantArtifact = existing.savantArtifact;
      return;
    }

    makeSavantArtifact(mavenDependency);

    // If the artifact has already been fetched, skip it
    if (cacheProcess.fetch(mavenDependency.savantArtifact, mavenDependency.savantArtifact.getArtifactFile(), null) != null) {
      System.out.println("Skipping artifact [" + mavenDependency.savantArtifact + "] because it already exists in the repository.");
      return;
    }

    boolean tryToDownload = true;
    Path pomFile = null;
    // Give the user another change to correct the version before we explode.
    while (tryToDownload) {
      pomFile = downloadItem(mavenDependency, mavenDependency.getPOM());
      if (pomFile == null) {
        System.out.println("Invalid Maven artifact [" + mavenDependency + "]. It doesn't appear to exist in the Maven repository. Is it correct?");
        String tryAgainAnswer = ask("Do you want to try again? Verify the version is correct, the version should be as it is defined in the Maven repository.", "y", "Invalid response",
            (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n")));

        tryToDownload = tryAgainAnswer.equals("y");
        if (tryToDownload) {
          mavenDependency.version = ask("Enter the corrected artifact version", mavenDependency.version, "Invalid response", StringUtils::isNotBlank);
          // update the savant artifact with the new version
          makeSavantArtifact(mavenDependency);
        }
      } else {
        tryToDownload = false;
      }
    }

    if (pomFile == null) {
      throw new RuntimeException("Invalid Maven artifact [" + mavenDependency + "]. It doesn't appear to exist in the Maven repository. Is it correct?");
    }

    if (debug) {
      try {
        System.out.println("POM is " + new String(Files.readAllBytes(pomFile)));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    final POM pom = MavenTools.parsePOM(pomFile, new SystemOutOutput(true));
    final Map<String, String> properties = pom.properties;
    mavenDependency.dependencies.addAll(pom.dependencies);

    // Load the parent POM's dependencies and properties
    POM current = mavenDependency;
    while (current.parentId != null) {
      MavenDependency parentArtifact = new MavenDependency(current.parentGroup, current.parentId, current.parentVersion);
      pomFile = downloadItem(parentArtifact, parentArtifact.getPOM());
      current.parent = MavenTools.parsePOM(pomFile, new SystemOutOutput(true));
      current.parent.properties.forEach(properties::putIfAbsent);
      current.parent.properties.forEach((key, value) -> properties.putIfAbsent("parent." + key, value));
      current.parent.properties.forEach((key, value) -> properties.putIfAbsent("project.parent." + key, value));
      current = current.parent;
    }

    // Replace the properties and resolve artifact versions from parent POMs
    mavenDependency.dependencies.forEach((dependency) -> {
      dependency.group = MavenTools.replaceProperties(dependency.group, properties);
      dependency.id = MavenTools.replaceProperties(dependency.id, properties);
      dependency.type = MavenTools.replaceProperties(dependency.type, properties);
      dependency.scope = MavenTools.replaceProperties(dependency.scope, properties);
      dependency.version = MavenTools.replaceProperties(dependency.version, properties);
      dependency.classifier = MavenTools.replaceProperties(dependency.classifier, properties);

      if (dependency.version == null) {
        // Attempt to resolve it
        dependency.version = pom.resolveDependencyVersion(dependency);

        // Version ok? call replaceProperties
        dependency.version = MavenTools.replaceProperties(dependency.version, properties);

        // If we're still null, ask for it, maybe the human is smarter than me...
        if (dependency.version == null) {
          dependency.version = ask("Unable to determine version for dependency [" + dependency + "]. Maven allows this, " +
                  "Savant does not. You must provide the correct version of the Maven artifact to use.",
              null, "You must supply a version", StringUtils::isNotBlank);
        }
      }

      if (dependency.scope == null) {
        // Attempt to resolve it
        dependency.scope = pom.resolveDependencyScope(dependency);

        // Scope ok? call replaceProperties
        dependency.scope = MavenTools.replaceProperties(dependency.scope, properties);

        // If we're still null, default to compile
        if (dependency.scope == null) {
          dependency.scope = "compile";
        }
      }

      if (dependency.optional == null) {
        // Attempt to resolve it
        dependency.optional = pom.resolveDependencyOptional(dependency);

        // Optional ok? call replaceProperties
        dependency.optional = MavenTools.replaceProperties(dependency.optional, properties);
      }
    });

    // Remove Test Dependencies if we're not using them before we check versions.
    mavenDependency.dependencies.removeIf(dependency -> !includeTestDependencies && dependency.scope.equalsIgnoreCase("test"));

    // Remove Optional Dependencies if we're not using them before we check versions.
    mavenDependency.dependencies.removeIf(dependency -> !includeOptionalDependencies && dependency.optional != null && dependency.optional.equals("true"));

    // Remove duplicates
    Set<MavenDependency> dependencies = new HashSet<>(mavenDependency.dependencies);
    mavenDependency.dependencies.clear();
    mavenDependency.dependencies.addAll(dependencies);

    // Ask which dependencies to include in the AMD
    mavenDependency.dependencies.removeIf((dependency) -> {
      if (prompt()) {
        // Use the optional flag if set
        String scope = dependency.scope;
        if (dependency.optional != null && dependency.optional.equalsIgnoreCase("true")) {
          scope = dependency.scope + "-optional";
        }

        // Ask if they want to keep it
        String includeString = ask("Include dependency [" + dependency + "] in scope [" + scope + "] in the Savant AMD file ([y]es/[n]o) ", "y", "Invalid response",
            (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n")));
        boolean include = includeString.equals("y");
        if (include) {
          dependency.scope = ask("  Enter scope for dependency (provided, compile, compile-optional, runtime, runtime-optional, test-compile, test-runtime). Default: ", scope, "Invalid response",
              (response) -> StringUtils.isNotBlank(response) && (response.equals("provided") || response.equals("compile") || response.equals("compile-optional") ||
                  response.equals("runtime") || response.equals("runtime-optional") || response.equals("test-compile") || response.equals("test-runtime")));
        }

        return !include;
      } else {
        return false;
      }

    });

    // Mark the maven artifact as visited and then traverse graph. After the graph has been traversed, remove the artifact
    // from the visited list because that list is only used to check for cycles
    cycleCheck.add(mavenDependency);
    visitedArtifacts.add(mavenDependency);
    mavenDependency.dependencies.forEach((dependency) -> buildMavenGraph(dependency, cycleCheck, visitedArtifacts));
    cycleCheck.remove(mavenDependency);
  }

  private void downloadAndProcess(MavenDependency mavenDependency) {
    // Check if the file already exists and if not, fetch it
    if (cacheProcess.fetch(mavenDependency.savantArtifact, mavenDependency.savantArtifact.getArtifactFile(), null) == null) {
      Path file = downloadItem(mavenDependency, mavenDependency.getMainFile());
      if (file == null) {
        throw new RuntimeException("Unable to download Maven artifact " + mavenDependency);
      }

      Path sourceFile = downloadItem(mavenDependency, mavenDependency.getSourceFile());
      ArtifactMetaData amd = new ArtifactMetaData(MavenTools.toSavantDependencies(mavenDependency), mavenDependency.savantArtifact.licenses);
      if (debug) {
        try {
          Path temp = ArtifactTools.generateXML(amd);
          System.out.println("Writing out AMD file");
          System.out.println(new String(Files.readAllBytes(temp)));
        } catch (IOException e) {
          // never
        }
      }

      Publication publication = new Publication(mavenDependency.savantArtifact, amd, file, sourceFile);
      service.publish(publication, publishWorkflow);
    }

    mavenDependency.dependencies.forEach(this::downloadAndProcess);
  }

  private Path downloadItem(MavenDependency mavenDependency, String item) {
    try {
      URI md5URI = NetTools.build("https://repo1.maven.org/maven2", mavenDependency.group.replace('.', '/'), mavenDependency.id, mavenDependency.version, item + ".md5");
      if (debug) {
        System.out.println(" " + md5URI);
      }
      Path md5File = NetTools.downloadToPath(md5URI, null, null, null);
      MD5 md5 = MD5.load(md5File);
      URI uri = NetTools.build("https://repo1.maven.org/maven2", mavenDependency.group.replace('.', '/'), mavenDependency.id, mavenDependency.version, item);
      if (debug) {
        System.out.println(" " + uri);
      }
      return NetTools.downloadToPath(uri, null, null, md5);
    } catch (IOException e) {
      throw new RuntimeException("ERROR", e);
    }
  }

  private List<License> getLicenses(MavenDependency mavenDependency) {
    List<License> licenses = licenseMappings.get(mavenDependency.group + ":" + mavenDependency.id);
    if (licenses == null) {
      String licenseNames = ask("License(s) for this artifact - comma-separated list of SPDX compliant identifiers", "Apache-2.0", "Invalid license. Please re-enter.\n",
          (answer) -> {
            String[] parts = answer.split("\\W*,\\W*");
            for (String part : parts) {
              try {
                License.parse(part, "Ignored but used for validation.");
              } catch (IllegalArgumentException e) {
                // Bad license
                return false;
              }
            }
            return true;
          });

      licenses = new ArrayList<>();
      String[] parts = licenseNames.split("\\W*,\\W*");
      for (String part : parts) {
        License license = License.parse(part, null);
        licenses.add(license);
      }

      licenseMappings.put(mavenDependency.group + ":" + mavenDependency.id, licenses);
    }

    return licenses;
  }

  private Version getSemanticVersion(String version) {
    boolean keep = false;
    if (isSemantic(version)) {

      if (prompt()) {
        System.out.printf("The version [%s] appears to be semantic. Do you want to keep it [y]?\n", version);
        String answer;
        try {
          answer = input.readLine();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        if (answer.equals("") || answer.equals("y")) {
          keep = true;
        }
      } else {
        keep = true;
      }
    } else {
      System.out.printf("The version [%s] is not semantic. You need to give the project a valid semantic version.\n", version);
    }

    if (!keep) {
      do {
        System.out.println("Enter the new version to use");
        try {
          version = input.readLine();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        if (version != null && version.length() > 0 && isSemantic(version)) {
          keep = true;
        } else {
          System.out.println("Invalid semantic version. Please re-enter.");
        }
      } while (!keep);
    }

    return new Version(version);
  }

  private boolean isSemantic(String version) {
    return semanticVersion.matcher(version).matches();
  }

  private void makeSavantArtifact(MavenDependency mavenDependency) {
    System.out.println();
    System.out.println("---------------------------------------------------------------------------------------------------------");
    System.out.println("Converting Maven artifact [" + mavenDependency + "] to a Savant Artifact");
    System.out.println("---------------------------------------------------------------------------------------------------------");

    String savantGroup = groupMappings.map(mavenDependency.group);
    if (!savantGroup.equals(mavenDependency.group)) {
      System.out.println("Mapped Maven group [" + mavenDependency.group + "] to Savant group [" + savantGroup + "]");
    } else if (!savantGroup.contains(".")) {
      savantGroup = ask("That group looks weaksauce. Enter the group to use with Savant", mavenDependency.group, null, null);

      // Store the mapping if they changed the group
      if (!mavenDependency.group.equals(savantGroup)) {
        groupMappings.add(mavenDependency.group, savantGroup);
      }
    }

    Version savantVersion = getSemanticVersion(mavenDependency.version);

    // Don't ask for the licenses if we already have it
    List<License> licenses = Collections.emptyList();
    mavenDependency.savantArtifact = new ReifiedArtifact(new ArtifactID(savantGroup, mavenDependency.id, mavenDependency.getArtifactName(), (mavenDependency.type == null ? "jar" : mavenDependency.type)), savantVersion, licenses);

    if (cacheProcess.fetch(mavenDependency.savantArtifact, mavenDependency.savantArtifact.getArtifactFile(), null) == null) {
      licenses = getLicenses(mavenDependency);
      mavenDependency.savantArtifact = new ReifiedArtifact(new ArtifactID(savantGroup, mavenDependency.id, mavenDependency.getArtifactName(), (mavenDependency.type == null ? "jar" : mavenDependency.type)), savantVersion, licenses);
    }
  }

  private boolean prompt() {
    String prompt = System.getenv("SAVANT_BRIDGE_PROMPT");
    return prompt == null || prompt.equals("true");
  }
}
