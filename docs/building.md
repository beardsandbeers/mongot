# Building MongoDB Search (mongot) from Source

For the independent standalone service, use the [standalone build and operating guide](standalone-mongot.md).
Its Docker quick start builds and packages the JVM and native dependencies inside Docker; host Java,
Bazel, and MongoDB are not required.

## Prerequisites

Before building, ensure you have the following installed:
- Java Development Kit (JDK)
- Python 3
- Git
- Make

## Quick Start

### Understanding the Build Process

This project has two main build commands that serve different purposes:

**1. `make build` - Compile and Test**

```bash
make build
```

This command compiles all the source code and runs basic validation. It:
- Compiles all Java source code under `src/`
- Creates compiled `.class` files and internal artifacts
- Validates that code can compile successfully
- Checks that pre-commit hooks are properly configured

**Important:** This does NOT create a runnable JAR file. The output is compiled code used for development, testing, and as a prerequisite for creating the actual deployable package.

**2. `make build.deploy.community` - Create Runnable JAR**

To build the actual deployable JAR file that you can run, use:

```bash
make build.deploy.community
```

This creates the complete, runnable MongoDB Search (mongot) package that includes the JAR file you can actually execute.

**For a specific platform:**
```bash
make build.deploy.community PLATFORM=linux_x86_64
```

Available platforms:
- `linux_x86_64` (default)
- Other platforms as defined in `//bazel/platforms`

### Recommended Build Workflow

For most users building from source, follow this sequence:

```bash
# Step 1: Compile and validate (catches errors early)
make build

# Step 2: Create the deployable JAR
make build.deploy.community

# Step 3: Run your JAR (see "Running Your Build" section below)
```

## Locating Your Build Artifacts

### Finding Build Output

After running `make build.deploy.community`, your deployable artifacts will be in Bazel's output directory.

**Quick way to find it:**
```bash
# Get the bazel-bin directory path
bazel info bazel-bin

# Or use the convenience symlink in your project directory
ls -la bazel-bin/deploy/
```

Your project directory will contain symlinks to the build output:
- `bazel-bin/` → symlink to the actual output directory
- `bazel-out/` → symlink to all build outputs

**The deployable will be at:**
```
bazel-bin/deploy/mongot-community
```

### Understanding Bazel Output Structure

Bazel stores build outputs in a structured directory outside your source tree (typically `~/.cache/bazel/` on Linux or `~/Library/Caches/bazel` on macOS). The exact path includes a hash of your workspace directory path, which is why the symlinks are helpful.

Inside `bazel-bin/`, you'll find:
- `bin/` - Compiled binaries and JARs
- `genfiles/` - Generated source files
- `testlogs/` - Test output logs

## Running Your Build

Once you've built the deployable with `make build.deploy.community`, you can run it:

```bash
# Navigate to the build output
cd bazel-bin/deploy/mongot-community

# Run the JAR (exact command depends on your project structure)
# Example:
java -jar mongot.jar

# Or if there's a startup script:
./bin/mongot
```

**Note:** The exact command to run your application will depend on how the project is packaged. Look in the `bazel-bin/deploy/mongot-community/` directory for startup scripts or JAR files.

## Verifying Your Build

Before considering your build complete, it's recommended to run:

```bash
make check
```

This will:
- Build the project
- Run linting checks to ensure code quality

## Running Tests

### Unit Tests

```bash
make test.unit
```

## Common Build Issues

### Issue: "pre-commit not installed" warning

**Solution:** Install pre-commit hooks:
```bash
pip install pre-commit
pre-commit install
```

### Issue: Build fails with dependency errors

**Solution:** Update dependencies:
```bash
make deps.update
```

### Issue: Need to check for outdated dependencies

**Solution:** Run the outdated check:
```bash
make deps.outdated
```

## Development Workflow

1. **Make code changes**
2. **Build and check:**
   ```bash
   make check
   ```
3. **Run tests:**
   ```bash
   make test.unit
   ```
4. **Build deployable:**
   ```bash
   make build.deploy.community
   ```

## Additional Commands

### Lint your code
```bash
make lint
```

### Format BUILD files
```bash
make tools.buildifier.fix
```

### Update dependencies
```bash
make deps.update
```

## Understanding the Build System

This project uses [Bazel](https://bazel.build/) as its build system, wrapped by [Bazelisk](https://github.com/bazelbuild/bazelisk) for version management. The Makefile provides convenient shortcuts to common Bazel commands. You don't need to install Bazel manually—the build process will handle this automatically through Bazelisk.

## Need Help?

If you encounter issues:
1. Ensure all prerequisites are installed
2. Try cleaning and rebuilding: `bazel clean` then `make build`
3. Check that you're using a supported platform
4. Review the output of `bazel info` for configuration details
