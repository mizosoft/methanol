#!/usr/bin/env bash

JAVADOC_SITE_PATH=api/latest

# Fail the script if one command fails
set -e

# Clean our site directory
rm -rf site

# Make the necessary files locatable by MkDocs
mkdir -p docs/adapters
cp -f methanol-gson/README.md docs/adapters/gson.md
cp -f methanol-jackson/README.md docs/adapters/jackson.md
cp -f methanol-jackson-flux/README.md docs/adapters/jackson_flux.md
cp -f methanol-jaxb/README.md docs/adapters/jaxb.md
cp -f methanol-protobuf/README.md docs/adapters/protobuf.md
cp -f methanol-brotli/README.md docs/brotli.md
cp -f methanol-benchmarks/README.md docs/benchmarks.md
cp -f CHANGELOG.md docs/CHANGELOG.md
cp -f CONTRIBUTING.md docs/CONTRIBUTING.md

# Ensure MkDocs & used theme are installed
python -m venv venv
chmod +x ./venv/bin/activate && ./venv/bin/activate
python -m pip install mkdocs-material

# Generate docs
python -m mkdocs build
./gradlew clean rootJavadoc

# Copy generated Javadoc site to main site directory
mkdir -p site/$JAVADOC_SITE_PATH
cp -rf build/docs/javadoc/* site/$JAVADOC_SITE_PATH

# Remove copied files, which is desirable when the script is run locally
rm -rf docs/adapters
rm -f docs/brotli.md
rm -f docs/benchmarks.md
rm -f docs/CHANGELOG.md
rm -f docs/CONTRIBUTING.md
