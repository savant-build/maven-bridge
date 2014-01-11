#!/bin/sh
working_dir=$(dirname $0)
classpath=
for f in $(ls $working_dir/../libs); do
  classpath=${classpath}:${working_dir}/../libs/${f}
done

java -cp ${classpath} org.savantbuild.dep.maven.Main $@