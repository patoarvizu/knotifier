#!/bin/sh
if [ $(which play) ]; then
  play dist
else
  activator dist
fi
version=`grep "version := " build.sbt | cut -d\" -f2`
mvn org.apache.maven.plugins:maven-deploy-plugin:2.8:deploy-file -Durl=http://onswipe-nexus.elasticbeanstalk.com/content/repositories/snapshots \
                                                                            -DrepositoryId=onswipe-nexus \
                                                                            -DgroupId=com.onswipe \
                                                                            -DartifactId=knotifier \
                                                                            -Dversion=$version \
                                                                            -Dpackaging=zip \
                                                                            -DgeneratePom=false \
                                                                            -Dfile=target/universal/knotifier-$version.zip
