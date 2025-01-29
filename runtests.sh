#!/bin/sh

# SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
#
# SPDX-License-Identifier: MIT

# The runtimes and libraries are so outdated that I run tests in a docker container
docker run -it --rm --mount type=bind,src=.,dst=/home/gradle/project \
       -w /home/gradle/project gradle:6-jdk8 \
       gradle test # To view (HTML) test reports, run "bash" and this gradle task from there
