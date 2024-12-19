#!/usr/bin/env bash

#
# Copyright (c) 2024 Moataz Abdelnasser
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

JAVADOC_SITE_PATH=api/latest

# Fail the script if one command fails.
set -e

# Ensure MkDocs & used theme are installed.
python -m venv venv
chmod +x ./venv/bin/activate && ./venv/bin/activate
python -m pip install mkdocs-material

# Make the necessary files locatable by MkDocs.
mkdir -p docs/adapters
cp -f README.md docs/index.md
cp -f methanol-gson/README.md docs/adapters/gson.md
cp -f methanol-jackson/README.md docs/adapters/jackson.md
cp -f methanol-jackson-flux/README.md docs/adapters/jackson_flux.md
cp -f methanol-jaxb/README.md docs/adapters/jaxb.md
cp -f methanol-jaxb-jakarta/README.md docs/adapters/jaxb_jakarta.md
cp -f methanol-moshi/README.md docs/adapters/moshi.md
cp -f methanol-protobuf/README.md docs/adapters/protobuf.md
cp -f methanol-kotlin/README.md docs/kotlin.md
cp -f methanol-redis/README.md docs/redis.md
cp -f methanol-brotli/README.md docs/brotli.md
cp -f methanol-benchmarks/README.md docs/benchmarks.md
cp -f CHANGELOG.md docs/CHANGELOG.md
cp -f CONTRIBUTING.md docs/CONTRIBUTING.md

# Clean our site directory.
rm -rf site

# Build website.
python -m mkdocs build

# Generate aggregate Javadoc for Java projects.
./gradlew aggregateJavadoc

# Generate docs for kotlin projects.
./gradlew dokkaHtmlMultiModule

# Merge Java & Kotlin documentation into the site directory. This seems to work decently as long as
# we use consistent module & output directory naming (done by the build scripts) and we exclude
# conflicting/inappropriate files from the Kotlin documentation.
mkdir -p site/$JAVADOC_SITE_PATH
echo "Copying Javadoc files"
rsync -rv build/docs/aggregateJavadoc/ site/$JAVADOC_SITE_PATH
echo "Copying KDoc files"
rsync -rv build/dokka/htmlMultiModule/ site/$JAVADOC_SITE_PATH --exclude="/index.html" \
 --exclude="/package-list"

# Remove copied files, which is desirable when the script is run locally.
rm -r docs/adapters
rm docs/kotlin.md
rm docs/redis.md
rm docs/brotli.md
rm docs/benchmarks.md
rm docs/CHANGELOG.md
rm docs/CONTRIBUTING.md
