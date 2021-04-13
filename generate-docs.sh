#!/usr/bin/env bash

JAVADOC_SITE_PATH=api/latest

# Fail the script if one command fails
set -e

# Clean our site directory
rm -rf site

# Make changelog locatable by MkDocs
cp -f CHANGELOG.md docs/CHANGELOG.md

# Ensure MkDocs & used theme are installed
python -m pip install mkdocs-material

# Generate docs
python -m mkdocs build
./gradlew clean rootJavadoc

# Copy generated Javadoc site to main site directory
mkdir -p site/$JAVADOC_SITE_PATH
cp -rf build/docs/javadoc/* site/$JAVADOC_SITE_PATH

# Remove copied changelog, which is desirable when the script is run locally
rm -f docs/CHANGELOG.md
