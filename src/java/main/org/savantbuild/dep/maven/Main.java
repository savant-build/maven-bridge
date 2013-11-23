/*
 * Copyright (c) 2001-2013, Inversoft, All Rights Reserved
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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Main entry point for the Savant-Maven-Bridge.
 *
 * @author Brian Pontarelli
 */
public class Main {
  /**
   * Entry.
   *
   * @param args CLI args.
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("Usage: savant-maven-bridge <directory>");
      System.exit(1);
    }

    // Get the working directory
    Path directory = Paths.get(args[0]);
    if (Files.isRegularFile(directory)) {
      System.err.println("Invalid working directory [" + directory + "]. It is a file");
      System.exit(1);
    } else if (!Files.isDirectory(directory)) {
      Files.createDirectories(directory);
    }

    // Load the group mapping file
    Map<String, String> mappings = loadMappingFile(directory);

    // Run the bridge
    SavantBridge bridge = new SavantBridge(directory, mappings);
    bridge.run();
  }

  private static Map<String, String> loadMappingFile(Path directory) throws IOException {
    Properties properties = new Properties();
    properties.load(Files.newBufferedReader(directory.resolve("maven-group-mappings.properties"), Charset.forName("UTF-8")));

    Map<String, String> result = new HashMap<>();
    properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
    return result;
  }
}
