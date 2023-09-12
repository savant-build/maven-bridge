/*
 * Copyright (c) 2014-2023, Inversoft Inc., All Rights Reserved
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
import org.savantbuild.dep.domain.ResolvableItem;
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
    this.cacheProcess = new CacheProcess(output, directory.toString(), null);
    this.publishWorkflow = new PublishWorkflow(this.cacheProcess);
    this.debug = debug;
  }

  public void run() {
    System.out.println("Prompt Enabled [" + prompt() + "]");

    includeTestDependencies = ask("Include test dependencies?", "n", "Invalid Response", (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n"))).equals("y");
    includeOptionalDependencies = ask("Include optional dependencies?", "n", "Invalid Response", (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n"))).equals("y");

    MavenArtifact mavenArtifact = new MavenArtifact();
    mavenArtifact.group = ask("Maven group (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenArtifact.id = ask("Maven artifact id (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenArtifact.version = ask("Maven artifact version (i.e. 3.0.GA.1)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);

    buildMavenGraph(mavenArtifact, new HashSet<>(), new HashSet<>());
    downloadAndProcess(mavenArtifact);
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
   * @param mavenArtifact The maven artifact to fetch the graph for.
   */
  private void buildMavenGraph(MavenArtifact mavenArtifact, Set<MavenArtifact> cycleCheck, Set<MavenArtifact> visitedArtifacts) {
    if (cycleCheck.contains(mavenArtifact)) {
      throw new RuntimeException("The Maven artifact you are trying to convert contains a cycle in its dependencies. The cycle is for the artifact [" + mavenArtifact + "]. Cycles are impossible in the real world, so it seems as though someone has jimmied the POM.");
    }

    MavenArtifact existing = visitedArtifacts.stream().filter(mavenArtifact::equals).findFirst().orElse(null);
    if (existing != null) {
      mavenArtifact.savantArtifact = existing.savantArtifact;
      return;
    }

    makeSavantArtifact(mavenArtifact);

    // If the artifact has already been fetched, skip it
    ResolvableItem item = new ResolvableItem(mavenArtifact.savantArtifact.id.group, mavenArtifact.savantArtifact.id.project, mavenArtifact.savantArtifact.id.name, mavenArtifact.version, mavenArtifact.savantArtifact.getArtifactFile());
    if (cacheProcess.fetch(item, null) != null) {
      System.out.println("Skipping artifact [" + mavenArtifact.savantArtifact + "] because it already exists in the repository.");
      return;
    }

    boolean tryToDownload = true;
    Path pomFile = null;
    // Give the user another change to correct the version before we explode.
    while (tryToDownload) {
      pomFile = downloadItem(mavenArtifact, mavenArtifact.getPOM());
      if (pomFile == null) {
        System.out.println("Invalid Maven artifact [" + mavenArtifact + "]. It doesn't appear to exist in the Maven repository. Is it correct?");
        String tryAgainAnswer = ask("Do you want to try again? Verify the version is correct, the version should be as it is defined in the Maven repository.", "y", "Invalid response",
            (response) -> StringUtils.isNotBlank(response) && (response.equals("y") || response.equals("n")));

        tryToDownload = tryAgainAnswer.equals("y");
        if (tryToDownload) {
          mavenArtifact.version = ask("Enter the corrected artifact version", mavenArtifact.version, "Invalid response", StringUtils::isNotBlank);
          // update the savant artifact with the new version
          makeSavantArtifact(mavenArtifact);
        }
      } else {
        tryToDownload = false;
      }
    }

    if (pomFile == null) {
      throw new RuntimeException("Invalid Maven artifact [" + mavenArtifact + "]. It doesn't appear to exist in the Maven repository. Is it correct?");
    }

    if (debug) {
      try {
        System.out.println("POM is " + new String(Files.readAllBytes(pomFile)));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    final POM pom = new POM(pomFile);
    final Map<String, String> properties = pom.properties;
    mavenArtifact.dependencies.addAll(pom.dependencies);

    // Load the parent POM's dependencies and properties
    POM current = pom;
    while (current.parentId != null) {
      MavenArtifact parentArtifact = new MavenArtifact(current.parentGroup, current.parentId, current.parentVersion);
      pomFile = downloadItem(parentArtifact, parentArtifact.getPOM());
      current.parent = new POM(pomFile);
      current.parent.properties.forEach(properties::putIfAbsent);
      current.parent.properties.forEach((key, value) -> properties.putIfAbsent("parent." + key, value));
      current.parent.properties.forEach((key, value) -> properties.putIfAbsent("project.parent." + key, value));
      mavenArtifact.dependencies.addAll(current.dependencies);
      current = current.parent;
    }

    // Replace the properties and resolve artifact versions from parent POMs
    mavenArtifact.dependencies.forEach((dependency) -> {
      dependency.group = replaceProperties(dependency.group, properties);
      dependency.id = replaceProperties(dependency.id, properties);
      dependency.type = replaceProperties(dependency.type, properties);
      dependency.scope = replaceProperties(dependency.scope, properties);
      dependency.version = replaceProperties(dependency.version, properties);
      dependency.classifier = replaceProperties(dependency.classifier, properties);

      if (dependency.version == null) {
        // Attempt to resolve it
        dependency.version = pom.resolveDependencyVersion(dependency);

        // Version ok? call replaceProperties
        dependency.version = replaceProperties(dependency.version, properties);

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
        dependency.scope = replaceProperties(dependency.scope, properties);

        // If we're still null, default to compile
        if (dependency.scope == null) {
          dependency.scope = "compile";
        }
      }

      if (dependency.optional == null) {
        // Attempt to resolve it
        dependency.optional = pom.resolveDependencyOptional(dependency);

        // Optional ok? call replaceProperties
        dependency.optional = replaceProperties(dependency.optional, properties);
      }
    });

    // Remove Test Dependencies if we're not using them before we check versions.
    mavenArtifact.dependencies.removeIf(dependency -> !includeTestDependencies && dependency.scope.equalsIgnoreCase("test"));

    // Remove Optional Dependencies if we're not using them before we check versions.
    mavenArtifact.dependencies.removeIf(dependency -> !includeOptionalDependencies && dependency.optional != null && dependency.optional.equals("true"));

    // Remove duplicates
    Set<MavenArtifact> dependencies = new HashSet<>(mavenArtifact.dependencies);
    mavenArtifact.dependencies.clear();
    mavenArtifact.dependencies.addAll(dependencies);

    // Ask which dependencies to include in the AMD
    mavenArtifact.dependencies.removeIf((dependency) -> {
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
    cycleCheck.add(mavenArtifact);
    visitedArtifacts.add(mavenArtifact);
    mavenArtifact.dependencies.forEach((dependency) -> buildMavenGraph(dependency, cycleCheck, visitedArtifacts));
    cycleCheck.remove(mavenArtifact);
  }

  private void downloadAndProcess(MavenArtifact mavenArtifact) {
    // Check if the file already exists and if not, fetch it
    ResolvableItem item = new ResolvableItem(mavenArtifact.savantArtifact.id.group, mavenArtifact.savantArtifact.id.project, mavenArtifact.savantArtifact.id.name, mavenArtifact.version, mavenArtifact.savantArtifact.getArtifactFile());
    if (cacheProcess.fetch(item, null) == null) {
      Path file = downloadItem(mavenArtifact, mavenArtifact.getMainFile());
      if (file == null) {
        throw new RuntimeException("Unable to download Maven artifact " + mavenArtifact);
      }

      Path sourceFile = downloadItem(mavenArtifact, mavenArtifact.getSourceFile());
      ArtifactMetaData amd = new ArtifactMetaData(mavenArtifact.getSavantDependencies(), mavenArtifact.savantArtifact.licenses);
      if (debug) {
        try {
          Path temp = ArtifactTools.generateXML(amd);
          System.out.println("Writing out AMD file");
          System.out.println(new String(Files.readAllBytes(temp)));
        } catch (IOException e) {
          // never
        }
      }

      Publication publication = new Publication(mavenArtifact.savantArtifact, amd, file, sourceFile);
      service.publish(publication, publishWorkflow);
    }

    mavenArtifact.dependencies.forEach(this::downloadAndProcess);
  }

  private Path downloadItem(MavenArtifact mavenArtifact, String item) {
    try {
      URI md5URI = NetTools.build("https://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item + ".md5");
      if (debug) {
        System.out.println(" " + md5URI);
      }
      Path md5File = NetTools.downloadToPath(md5URI, null, null, null);
      MD5 md5 = MD5.load(md5File);
      URI uri = NetTools.build("https://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item);
      if (debug) {
        System.out.println(" " + uri);
      }
      return NetTools.downloadToPath(uri, null, null, md5);
    } catch (IOException e) {
      throw new RuntimeException("ERROR", e);
    }
  }

  private List<License> getLicenses(MavenArtifact mavenArtifact) {
    List<License> licenses = licenseMappings.get(mavenArtifact.group + ":" + mavenArtifact.id);
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
        // TODO : Seems like we should catch LicenseException here and allow the user to retry.
        //        - Currently we puke and you have to start over.
        //        - Ideally on a parse failure, we'd print out the valid SPDX names,
        //          or provide a 'help' option to ask for the valid SPDX names.
        //
        // TODO : If you specify 'Other' it will fail to parse because we need text.
        //        We used to have the ability to specify 'Other' and provide text.
        //        Is that path intentionally no longer supported, or can we add it back?

        License license = License.parse(part, null);
        licenses.add(license);
      }

      licenseMappings.put(mavenArtifact.group + ":" + mavenArtifact.id, licenses);
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

  private void makeSavantArtifact(MavenArtifact mavenArtifact) {
    System.out.println();
    System.out.println("---------------------------------------------------------------------------------------------------------");
    System.out.println("Converting Maven artifact [" + mavenArtifact + "] to a Savant Artifact");
    System.out.println("---------------------------------------------------------------------------------------------------------");

    String savantGroup = groupMappings.map(mavenArtifact.group);
    if (!savantGroup.equals(mavenArtifact.group)) {
      System.out.println("Mapped Maven group [" + mavenArtifact.group + "] to Savant group [" + savantGroup + "]");
    } else if (!savantGroup.contains(".")) {
      savantGroup = ask("That group looks weaksauce. Enter the group to use with Savant", mavenArtifact.group, null, null);

      // Store the mapping if they changed the group
      if (!mavenArtifact.group.equals(savantGroup)) {
        groupMappings.add(mavenArtifact.group, savantGroup);
      }
    }

    Version savantVersion = getSemanticVersion(mavenArtifact.version);

    // Don't ask for the licenses if we already have it
    List<License> licenses = Collections.emptyList();
    mavenArtifact.savantArtifact = new ReifiedArtifact(new ArtifactID(savantGroup, mavenArtifact.id, mavenArtifact.getArtifactName(), (mavenArtifact.type == null ? "jar" : mavenArtifact.type)), savantVersion, licenses);

    ResolvableItem item = new ResolvableItem(mavenArtifact.savantArtifact.id.group, mavenArtifact.savantArtifact.id.project, mavenArtifact.savantArtifact.id.name, mavenArtifact.version, mavenArtifact.savantArtifact.getArtifactFile());
    if (cacheProcess.fetch(item, null) == null) {
      licenses = getLicenses(mavenArtifact);
      mavenArtifact.savantArtifact = new ReifiedArtifact(new ArtifactID(savantGroup, mavenArtifact.id, mavenArtifact.getArtifactName(), (mavenArtifact.type == null ? "jar" : mavenArtifact.type)), savantVersion, licenses);
    }
  }

  private boolean prompt() {
    String prompt = System.getenv("SAVANT_BRIDGE_PROMPT");
    return prompt == null || prompt.equals("true");
  }

  private String replaceProperties(String value, Map<String, String> properties) {
    if (value == null) {
      return null;
    }

    for (String key : properties.keySet()) {
      value = value.replace("${" + key + "}", properties.get(key));
    }

    return value;
  }
}
