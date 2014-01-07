/*
 * Copyright (c) 2013, Inversoft Inc., All Rights Reserved
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

import org.apache.commons.lang.StringUtils;
import org.savantbuild.dep.DefaultDependencyService;
import org.savantbuild.dep.domain.Artifact;
import org.savantbuild.dep.domain.ArtifactID;
import org.savantbuild.dep.domain.ArtifactMetaData;
import org.savantbuild.dep.domain.License;
import org.savantbuild.dep.domain.Publication;
import org.savantbuild.dep.domain.Version;
import org.savantbuild.dep.domain.VersionException;
import org.savantbuild.security.MD5;
import org.savantbuild.net.NetTools;
import org.savantbuild.dep.workflow.PublishWorkflow;
import org.savantbuild.dep.workflow.process.CacheProcess;
import org.savantbuild.output.Output;
import org.savantbuild.output.SystemOutOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Arrays.asList;

/**
 * The bridge between Maven artifacts and Savant artifacts.
 *
 * @author Brian Pontarelli
 */
public class SavantBridge {
  private final CacheProcess cacheProcess;

  private final GroupMappings groupMappings;

  private final BufferedReader input;

  private final Map<String, License> licenseMappings = new HashMap<>();

  private final PublishWorkflow publishWorkflow;

  private final DefaultDependencyService service;

  public SavantBridge(Path directory, GroupMappings groupMappings) {
    this.input = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")));
    this.groupMappings = groupMappings;

    Output output = new SystemOutOutput(true);
    this.service = new DefaultDependencyService(output);

    // Cache only workflow that will check if the artifact coming from Maven already exists in our repository
    this.cacheProcess = new CacheProcess(output, directory.toString());
    this.publishWorkflow = new PublishWorkflow(this.cacheProcess);
  }

  public void run() {
    MavenArtifact mavenArtifact = new MavenArtifact();
    mavenArtifact.group = ask("Maven group (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenArtifact.id = ask("Maven artifact id (i.e. commons-collections)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);
    mavenArtifact.version = ask("Maven artifact version (i.e. 3.0.GA.1)", null, "Invalid input. Please re-enter", StringUtils::isNotBlank);

    buildMavenGraph(mavenArtifact, new HashSet<>());
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
  private void buildMavenGraph(MavenArtifact mavenArtifact, Set<MavenArtifact> visitedArtifacts) {
    if (visitedArtifacts.contains(mavenArtifact)) {
      throw new RuntimeException("The Maven artifact you are trying to convert contains a cycle in its dependencies. The cycle is for the artifact [" + mavenArtifact + "]. Cycles are impossible in the real world, so it seems as though someone has jimmied the POM.");
    }

    makeSavantArtifact(mavenArtifact);

    // If the artifact has already been fetched, skip it
    if (cacheProcess.fetch(mavenArtifact.savantArtifact, mavenArtifact.savantArtifact.getArtifactFile(), null) != null) {
      return;
    }

    Path pomFile = downloadItem(mavenArtifact, mavenArtifact.getPOM());
    final POM pom = new POM(pomFile);
    final Map<String, String> properties = pom.properties;
    mavenArtifact.dependencies = new ArrayList<>(pom.dependencies);

    // Load the parent POM's dependencies and properties
    POM current = pom;
    while (current.parentId != null) {
      MavenArtifact parentArtifact = new MavenArtifact(current.parentGroup, current.parentId, current.parentVersion);
      pomFile = downloadItem(parentArtifact, parentArtifact.getPOM());
      current.parent = new POM(pomFile);
      properties.putAll(current.properties);
      mavenArtifact.dependencies.addAll(current.dependencies);
      current = current.parent;
    }

    // Replace the properties and resolve artifact versions from parent POMs
    mavenArtifact.dependencies.forEach((dependency) -> {
      dependency.group = replaceProperties(dependency.group, properties);
      dependency.id = replaceProperties(dependency.id, properties);
      dependency.version = replaceProperties(dependency.version, properties);
      dependency.type = replaceProperties(dependency.type, properties);
      dependency.scope = replaceProperties(dependency.scope, properties);

      if (dependency.version == null) {
        dependency.version = pom.resolveDependencyVersion(dependency);
        if (dependency.version == null) {
          throw new RuntimeException("Unable to determine version for dependency [" + dependency + "]");
        }
      }
    });

    visitedArtifacts.add(mavenArtifact);
    mavenArtifact.dependencies.forEach((dependency) -> buildMavenGraph(dependency, visitedArtifacts));
    visitedArtifacts.remove(mavenArtifact);
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

  private void makeSavantArtifact(MavenArtifact mavenArtifact) {
    System.out.println();
    System.out.println("---------------------------------------------------------------------------------------------------------");
    System.out.println("Converting Maven artifact [" + mavenArtifact + "] to a Savant Artifact");
    System.out.println("---------------------------------------------------------------------------------------------------------");

    String savantGroup = groupMappings.map(mavenArtifact.group);
    if (!savantGroup.contains(".")) {
      savantGroup = ask("That group looks weaksauce. Enter the group to use with Savant", mavenArtifact.group, null, null);

      // Store the mapping if they changed the group
      if (!mavenArtifact.group.equals(savantGroup)) {
        groupMappings.add(mavenArtifact.group, savantGroup);
      }
    } else {
      savantGroup = mavenArtifact.group;
    }

    Version savantVersion = getSemanticVersion(mavenArtifact.version);
    License license = getLicense(mavenArtifact);

    mavenArtifact.savantArtifact = new Artifact(new ArtifactID(savantGroup, mavenArtifact.id, mavenArtifact.id, (mavenArtifact.type == null ? "jar" : mavenArtifact.type)), savantVersion, license);
  }

  private void downloadAndProcess(MavenArtifact mavenArtifact) {
    // Check if the file already exists and if not, fetch it
    if (cacheProcess.fetch(mavenArtifact.savantArtifact, mavenArtifact.savantArtifact.getArtifactFile(), null) == null) {
      Path file = downloadItem(mavenArtifact, mavenArtifact.getMainFile());
      if (file == null) {
        throw new RuntimeException("Unable to download Maven artifact " + mavenArtifact);
      }

      Path sourceFile = downloadItem(mavenArtifact, mavenArtifact.getSourceFile());
      ArtifactMetaData amd = new ArtifactMetaData(mavenArtifact.getSavantDependencies(), mavenArtifact.savantArtifact.license);
      Publication publication = new Publication(mavenArtifact.savantArtifact, amd, file, sourceFile);
      service.publish(publication, publishWorkflow);
    }

    mavenArtifact.dependencies.forEach(this::downloadAndProcess);
  }

  private Path downloadItem(MavenArtifact mavenArtifact, String item) {
    try {
      URI md5URI = NetTools.build("http://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item + ".md5");
      Path md5File = NetTools.downloadToPath(md5URI, null, null, null);
      MD5 md5 = MD5.load(md5File);
      URI uri = NetTools.build("http://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item);
      return NetTools.downloadToPath(uri, null, null, md5);
    } catch (URISyntaxException | IOException e) {
      throw new RuntimeException("Invalid URI", e);
    }
  }

  private License getLicense(MavenArtifact mavenArtifact) {
    License license = licenseMappings.get(mavenArtifact.group + ":" + mavenArtifact.id);
    if (license == null) {
      String licenseName = ask("License for this artifact (" + asList(License.values()) + ")", License.Apachev2.toString(), "Invalid license. Please re-enter.\n",
          (answer) -> {
            try {
              License.valueOf(answer);
              return true;
            } catch (IllegalArgumentException e) {
              // Bad license
              return false;
            }
          });

      license = License.valueOf(licenseName);
      licenseMappings.put(mavenArtifact.group + ":" + mavenArtifact.id, license);
    }

    return license;
  }

  private Version getSemanticVersion(String version) {
    boolean keep = false;
    if (isSemantic(version)) {
      System.out.printf("The version [%s] appears to be semantic. Does you want to keep it [y]?\n", version);
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

        if (isSemantic(version)) {
          keep = true;
        } else {
          System.out.println("Invalid semantic version. Please re-enter.");
        }
      } while (!keep);
    }

    return new Version(version);
  }

  private boolean isSemantic(String version) {
    try {
      new Version(version);
    } catch (VersionException e) {
      return false;
    }

    return true;
  }
}
