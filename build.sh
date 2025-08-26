#!/bin/bash

# -----------------------------------------------------------------------------
# Build Script for Java Application (JDK 21+)
#
# Purpose: Compiles and packages a simple Java app into an executable JAR.
# Source:  src/ directory with BettingServer.java (no package declaration)
# Output:  betting-app.jar (can be run with: java -jar betting-app.jar)
# Requires: JDK 21 or later (for modern 'jar' command syntax)
# -----------------------------------------------------------------------------

echo "📦 Starting build process..."

# Step 1: Clean up previous builds
echo "🧹 Cleaning up old files..."
rm -rf bin/ betting-app.jar

# Step 2: Create output directory for compiled classes
mkdir -p bin
echo "✅ Created output directory: bin/"

# Step 3: Compile all Java source files
echo "🔨 Compiling Java source files..."
javac -d bin src/*.java src/http/*.java src/domain/*.java src\domain\model\*.java src/utils/*.java

# Check if compilation succeeded
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed. Please check your Java code."
    exit 1
fi
echo "✅ Compilation successful."

# Step 4: Package into executable JAR
# Note: BettingServer.java has no package, so main class is just 'BettingServer'
echo "📦 Creating executable JAR: betting-app.jar..."
jar --create \
    --file=betting-app.jar \
    --main-class=BettingServer \
    -C bin .

if [ $? -ne 0 ]; then
    echo "❌ JAR creation failed."
    exit 1
fi

echo "✅ JAR created successfully: betting-app.jar"

# Final instructions
echo "🎉 Build completed!"
echo "➡️  Run the app with: java -jar betting-app.jar"