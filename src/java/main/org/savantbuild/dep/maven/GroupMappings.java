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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Manages group mappings between Maven and Savant. This is a set of persistent mappings that are stored in a properties
 * file.
 *
 * @author Brian Pontarelli
 */
public class GroupMappings {
  private final Properties properties;

  private final Path file;

  public GroupMappings(Path directory) throws IOException {
    properties = new Properties();
    file = directory.resolve("maven-group-mappings.properties");
    if (Files.isRegularFile(file)) {
      try (BufferedReader bufferedReader = Files.newBufferedReader(file, Charset.forName("UTF-8"))) {
        properties.load(bufferedReader);
      }
    }
  }

  public void store() throws IOException {
    if (!Files.isRegularFile(file)) {
      Files.createDirectories(file.getParent());
      Files.createFile(file);
    }

    try (BufferedWriter bufferedWriter = Files.newBufferedWriter(file, Charset.forName("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING)) {
      properties.store(bufferedWriter, null);
    }
  }

  /**
   * Adds the given mapping.
   *
   * @param mavenGroup The Maven group.
   * @param savantGroup The Savant group.
   */
  public void add(String mavenGroup, String savantGroup) {
    properties.setProperty(mavenGroup, savantGroup);
  }

  public String map(String mavenGroup) {
    String savantGroup = properties.getProperty(mavenGroup);
    return (savantGroup != null) ? savantGroup : mavenGroup;
  }
}
