#!/bin/sh

docker run -it --rm --mount type=bind,src=.,dst=/home/gradle/project \
       -w /home/gradle/project gradle:6-jdk8 \
       gradle --rerun-tasks -b test/build.gradle test # To view log output, run "bash" and this gradle task from there
