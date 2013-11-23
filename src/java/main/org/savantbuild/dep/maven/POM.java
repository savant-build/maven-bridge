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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.List;

/**
 * The necessary information from the POM.
 *
 * @author Brian Pontarelli
 */
public class POM {
  public List<MavenArtifact> dependencies;

  public String parentGroup;
  public String parentId;
  public String parentVersion;
  public String parentPath;

  public POM(Path file) {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document document = builder.parse(file.toFile());
    NodeList dependenciesElements = document.getElementsByTagName("dependencies");
    if (dependenciesElements.getLength() > 0) {
      Element dependenciesElement = (Element) dependenciesElements.item(0);
      NodeList dependencyElements = dependenciesElement.getChildNodes();
      for (int i = 0; i < dependencyElements.getLength(); i++) {
        Element dependencyElement = (Element) dependencyElements.item(i);
        parentGroup = dependencyElement.getElementsByTagName("groupId").item(0).getTextContent();
        parentId = dependencyElement.getElementsByTagName("artifactId").item(0).getTextContent();
        parentVersion = dependencyElement.getElementsByTagName("version").item(0).getTextContent();
      }
    }

    NodeList parentElements = document.getElementsByTagName("parent");
    if (parentElements.getLength() == 1) {
      parentGroup = ((Element) parentElements.item(0)).getElementsByTagName("groupId").item(0).getTextContent();
      parentId = ((Element) parentElements.item(0)).getElementsByTagName("artifactId").item(0).getTextContent();
      parentVersion = ((Element) parentElements.item(0)).getElementsByTagName("version").item(0).getTextContent();
      parentPath = ((Element) parentElements.item(0)).getElementsByTagName("version").item(0).getTextContent();
    }
  }
}
