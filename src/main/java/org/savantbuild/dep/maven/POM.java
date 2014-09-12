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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

/**
 * The necessary information from the POM.
 *
 * @author Brian Pontarelli
 */
public class POM {
  public List<MavenArtifact> dependencies = new ArrayList<>();

  public List<MavenArtifact> dependenciesDefinitions = new ArrayList<>();

  public POM parent;

  public String parentGroup;

  public String parentId;

  public String parentVersion;

  public Map<String, String> properties = new HashMap<>();

  public String version;

  public POM(Path file) throws RuntimeException {
    SAXBuilder builder = new SAXBuilder();
    try {
      Element pomElement = builder.build(file.toFile()).getRootElement();
      version = pomElement.getChildText("version", pomElement.getNamespace());
      if (version != null) {
        properties.put("project.version", version);
      }
      if (pomElement.getChildText("groupId", pomElement.getNamespace()) != null) {
        properties.put("project.groupId", pomElement.getChildText("groupId", pomElement.getNamespace()));
      }
      if (pomElement.getChildText("artifactId", pomElement.getNamespace()) != null) {
        properties.put("project.artifactId", pomElement.getChildText("artifactId", pomElement.getNamespace()));
      }
      if (pomElement.getChildText("name", pomElement.getNamespace()) != null) {
        properties.put("project.name", pomElement.getChildText("name", pomElement.getNamespace()));
      }
      if (pomElement.getChildText("packaging", pomElement.getNamespace()) != null) {
        properties.put("project.packaging", pomElement.getChildText("packaging", pomElement.getNamespace()));
      }

      // Grab the parent info
      Element parent = pomElement.getChild("parent", pomElement.getNamespace());
      if (parent != null) {
        parentGroup = parent.getChildText("groupId", pomElement.getNamespace());
        parentId = parent.getChildText("artifactId", pomElement.getNamespace());
        parentVersion = parent.getChildText("version", pomElement.getNamespace());
      }

      // Grab the properties
      Element properties = pomElement.getChild("properties", pomElement.getNamespace());
      if (properties != null) {
        properties.getChildren().forEach((element) -> this.properties.put(element.getName(), element.getTextTrim()));
    }

      // Grab the dependencies (top-level)
      Element dependencies = pomElement.getChild("dependencies", pomElement.getNamespace());
      if (dependencies != null) {
        dependencies.getChildren().forEach((element) -> this.dependencies.add(parseArtifact(element)));
      }

      // Grab the dependencyManagement info (top-level)
      Element dependencyManagement = pomElement.getChild("dependencyManagement", pomElement.getNamespace());
      if (dependencyManagement != null) {
        Element depMgntDeps = dependencyManagement.getChild("dependencies", dependencyManagement.getNamespace());
        depMgntDeps.getChildren().forEach((element) -> this.dependenciesDefinitions.add(parseArtifact(element)));
      }
    } catch (JDOMException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  public String resolveDependencyVersion(MavenArtifact dependency) {
    Optional<MavenArtifact> optional = dependenciesDefinitions.stream().filter((def) -> def.group.equals(dependency.group) && def.id.equals(dependency.id)).findFirst();
    if (!optional.isPresent() && parent != null) {
      return parent.resolveDependencyVersion(dependency);
    }

    if (optional.isPresent()) {
      return optional.get().version;
    }

    return null;
  }

  private MavenArtifact parseArtifact(Element element) {
    MavenArtifact artifact = new MavenArtifact();
    artifact.group = element.getChildText("groupId", element.getNamespace());
    artifact.id = element.getChildText("artifactId", element.getNamespace());
    artifact.version = element.getChildText("version", element.getNamespace());
    artifact.type = element.getChildText("type", element.getNamespace());
    artifact.optional = toBoolean(element.getChildText("optional", element.getNamespace()));
    artifact.scope = element.getChildText("scope", element.getNamespace());
    if (artifact.scope == null) {
      artifact.scope = "compile";
    }
    if (artifact.optional) {
      artifact.scope += "-optional";
    }

    List<Element> exclusions = element.getChildren("exclusions", element.getNamespace());
    if (exclusions.size() > 0) {
      System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!! WARNING !!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
      System.out.println("This Maven artifact has a dependency [" + artifact + "] with exclusions " + exclusions);
      System.out.println("This indicates that the artifact [" + artifact + "] declared a bad dependency or declared an optional dependency as required.");
      System.out.println("There isn't much we can do here since Savant doesn't allow exclusions because they should not occur when dependencies are listed and configured correctly.");
      System.out.println();
    }

    return artifact;
  }

  private boolean toBoolean(String value) {
    return value != null && Boolean.parseBoolean(value);
  }
}
