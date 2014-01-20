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

import java.util.ArrayList;
import java.util.List;

import org.savantbuild.dep.domain.Artifact;
import org.savantbuild.dep.domain.Dependencies;
import org.savantbuild.dep.domain.Dependency;
import org.savantbuild.dep.domain.DependencyGroup;

/**
 * Maven artifact.
 *
 * @author Brian Pontarelli
 */
public class MavenArtifact {
  public List<MavenArtifact> dependencies = new ArrayList<>();

  public List<MavenArtifact> exclusions = new ArrayList<>();

  public String group;

  public String id;

  public boolean optional;

  public Artifact savantArtifact;

  public String scope;

  public String type;

  public String version;

  public MavenArtifact() {
  }

  public MavenArtifact(String group, String id, String version) {
    this.group = group;
    this.id = id;
    this.version = version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final MavenArtifact that = (MavenArtifact) o;
    return group.equals(that.group) && id.equals(that.id) &&
        (type != null ? type.equals(that.type) : that.type == null) &&
        (version != null ? version.equals(that.version) : that.version == null);
  }

  @Override
  public int hashCode() {
    int result = group.hashCode();
    result = 31 * result + id.hashCode();
    result = 31 * result + (type != null ? type.hashCode() : 0);
    result = 31 * result + (version != null ? version.hashCode() : 0);
    return result;
  }

  public String getMainFile() {
    return id + "-" + version + "." + (type == null ? "jar" : type);
  }

  public String getPOM() {
    return id + "-" + version + ".pom";
  }

  public Dependencies getSavantDependencies() {
    Dependencies savantDependencies = new Dependencies();
    dependencies.forEach((dependency) -> {
      DependencyGroup savantDependencyGroup = savantDependencies.groups.get(dependency.scope);
      if (savantDependencyGroup == null) {
        savantDependencyGroup = new DependencyGroup(dependency.scope, true);
        savantDependencies.groups.put(savantDependencyGroup.type, savantDependencyGroup);
      }

      savantDependencyGroup.dependencies.add(new Dependency(dependency.savantArtifact.id, dependency.savantArtifact.version, dependency.optional));
    });

    return savantDependencies;
  }

  public String getSourceFile() {
    return id + "-" + version + "-sources." + (type == null ? "jar" : type);
  }

  public String toString() {
    return group + ":" + id + ":" + version + ":" + (type == null ? "jar" : type);
  }
}
