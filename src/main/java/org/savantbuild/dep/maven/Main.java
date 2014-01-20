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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    if (args.length != 1 && args.length != 2) {
      System.err.println("Usage: savant-maven-bridge [--debug] <directory>");
      System.exit(1);
    }

    boolean debug = args.length == 2 && args[0].equals("--debug");
    String directoryName = args.length == 1 ? args[0] : args[2];

    // Get the working directory
    Path directory = Paths.get(directoryName);
    if (Files.isRegularFile(directory)) {
      System.err.println("Invalid working directory [" + directory + "]. It is a file");
      System.exit(1);
    } else if (!Files.isDirectory(directory)) {
      Files.createDirectories(directory);
    }

    // Load the group mapping file
    GroupMappings mappings = new GroupMappings(directory);

    // Run the bridge
    SavantBridge bridge = new SavantBridge(directory, mappings, debug);
    bridge.run();

    // Save the new mappings out
    mappings.store();
  }
}
