#!/bin/sh

# SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
#
# SPDX-License-Identifier: MIT

# The runtimes and libraries are so outdated that I run tests in a docker container.
#
# I first install my favorite hubitat_ci fork into a docker volume like so:
#   docker run --rm -it --mount type=bind,src=.,dst=/home/gradle/project -w /home/gradle/project \
#   -v mavenLocal:/home/gradle/.m2 gradle:8-jdk11 \
#   gradle "-Dmaven.repo.local=/home/gradle/.m2" publishToMavenLocal

docker run -it --rm --mount type=bind,src=.,dst=/home/gradle/project -w /home/gradle/project \
    -v mavenLocal:/home/gradle/.m2 gradle:8-jdk11 gradle "-Dmaven.repo.local=/home/gradle/.m2" test
