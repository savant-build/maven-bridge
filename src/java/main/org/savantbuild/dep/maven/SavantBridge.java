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

import org.savantbuild.dep.DefaultDependencyService;
import org.savantbuild.dep.DependencyService;
import org.savantbuild.dep.domain.Artifact;
import org.savantbuild.dep.domain.ArtifactID;
import org.savantbuild.dep.domain.License;
import org.savantbuild.dep.domain.Version;
import org.savantbuild.dep.domain.VersionException;
import org.savantbuild.dep.io.MD5;
import org.savantbuild.dep.net.NetTools;
import org.savantbuild.dep.workflow.FetchWorkflow;
import org.savantbuild.dep.workflow.PublishWorkflow;
import org.savantbuild.dep.workflow.Workflow;
import org.savantbuild.dep.workflow.process.CacheProcess;
import org.testng.util.Strings;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static java.util.Arrays.asList;

/**
 * The bridge between Maven artifacts and Savant artifacts.
 *
 * @author Brian Pontarelli
 */
public class SavantBridge {
  private final Path directory;

  private final Map<String, String> groupMappings;

  private final DefaultDependencyService service;

  public SavantBridge(Path directory, Map<String, String> groupMappings) {
    this.directory = directory;
    this.groupMappings = groupMappings;
    this.service = new DefaultDependencyService();

    // Cache only workflow that will check if the artifact coming from Maven already exists in our repository
    Workflow workflow = new Workflow(new FetchWorkflow(new CacheProcess(directory.toString())), new PublishWorkflow(new CacheProcess(directory.toString())));

  }

  public void run() throws IOException {
    MavenArtifact mavenArtifact = new MavenArtifact();
    mavenArtifact.group = ask("Maven group (id i.e. commons-collections)", null, "Invalid input. Please re-enter",
        (answer) -> !Strings.isNullOrEmpty(answer));
    mavenArtifact.id = ask("Maven artifact id (i.e. commons-collections)", null, "Invalid input. Please re-enter",
        (answer) -> !Strings.isNullOrEmpty(answer));
    mavenArtifact.version = ask("Maven artifact version (i.e. 3.0.GA.1)", null, "Invalid input. Please re-enter",
        (answer) -> !Strings.isNullOrEmpty(answer));


    buildMavenGraph(mavenArtifact);

    downloadAndProcess(mavenArtifact);
  }

  private void buildMavenGraph(MavenArtifact mavenArtifact) {
    Path pomFile = downloadItem(mavenArtifact, mavenArtifact.getPOM());
    POM pom = parsePOM(pomFile);
  }

  private String ask(String message, String defaultValue, String errorMessage, Predicate<String> predicate) {
    String answer;
    boolean valid;
    do {
      System.console().printf(message + (defaultValue != null ? " [" + defaultValue + "]" : "") + "?\n");
      answer = System.console().readLine();
      if (Strings.isNullOrEmpty(answer) && defaultValue != null) {
        return defaultValue;
      }

      if (predicate != null) {
        valid = predicate.test(answer);
        if (!valid) {
          System.console().printf(errorMessage);
        }
      } else {
        valid = true;
      }
    } while (valid);

    return answer;
  }

  private void downloadAndProcess(MavenArtifact mavenArtifact) {
    String savantGroup;
    if (groupMappings.containsKey(mavenArtifact.group)) {
      savantGroup = groupMappings.get(mavenArtifact.group);
    } else if (!mavenArtifact.group.contains(".")) {
      savantGroup = ask("That group looks weaksauce. Enter the group to use with Savant", mavenArtifact.group, null, null);

      // Store the mapping if they changed the group
      if (!mavenArtifact.group.equals(savantGroup)) {
        groupMappings.put(mavenArtifact.group, savantGroup);
      }
    } else {
      savantGroup = mavenArtifact.group;
    }

    Version savantVersion = getSemanticVersion(mavenArtifact.version);
    License license = getLicense();

    // We have everything
    Path file = downloadArtifact(mavenArtifact);
    service.publish(new Artifact(new ArtifactID(savantGroup, mavenArtifact.id, mavenArtifact.id, mavenArtifact.type), savantVersion, license), file);

    mavenArtifact.dependencies.forEach(this::downloadAndProcess);
  }

  private Path downloadItem(MavenArtifact mavenArtifact, String item) {
    try {
      URI md5URI = NetTools.build("http://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item + ".md5");
      Path md5File = NetTools.downloadToPath(md5URI, null, null, null);
      MD5 md5 = MD5.fromPath(md5File);
      URI uri = NetTools.build("http://repo1.maven.org/maven2", mavenArtifact.group.replace('.', '/'), mavenArtifact.id, mavenArtifact.version, item);
      return NetTools.downloadToPath(uri, null, null, md5);
    } catch (URISyntaxException | IOException e) {
      throw new RuntimeException("Invalid URI", e);
    }
  }

  private License getLicense() {
    String licenseName = ask("License for this artifact (" + asList(License.values()) + ") [Apachev2]?", License.Apachev2.toString(), null,
        (answer) -> {
          try {
            License.valueOf(answer);
            return true;
          } catch (IllegalArgumentException e) {
            // Bad license
            return false;
          }
        });
    return License.valueOf(licenseName);
  }

  private Version getSemanticVersion(String version) {
    boolean keep = false;
    if (isSemantic(version)) {
      System.out.println("The version [" + version + "] appears to be semantic. Does you want to keep it (y/n)?");
      String answer = System.console().readLine();
      if (answer.equals("y")) {
        keep = true;
      }
    } else {
      System.out.println("The version [" + version + "] is not semantic. You need to give the project a valid semantic version.");
    }

    if (!keep) {
      do {
        System.out.println("Enter the new version to use");
        version = System.console().readLine();
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
