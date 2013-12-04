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
package org.savantbuild.dep;

import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for unit tests.
 *
 * @author Brian Pontarelli
 */
@Test(groups = "unit")
public abstract class BaseUnitTest {
  public static Path projectDir;

  @BeforeSuite
  public static void beforeSuite() {
    projectDir = Paths.get("");
    if (!Files.isRegularFile(projectDir.resolve("LICENSE"))) {
      projectDir = projectDir.resolve("savant-maven-bridge");
    }
  }
}
