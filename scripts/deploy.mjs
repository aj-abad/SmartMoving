#!/usr/bin/env node
// Build the Fabric mod and copy its loadable jar into the launcher profile's mods folder,
// `<.minecraft>/<minecraft_version>-fabric/mods` (for example `26.2-fabric/mods`).
//
//   pnpm deploy            build, then copy
//   pnpm build             build only
//   pnpm copy              copy an existing build
//
// Use --no-prune to retain older smartmoving-*.jar files. MINECRAFT_PROFILE overrides the
// profile directory name, and MINECRAFT_MODS overrides the whole destination.

import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

const root = process.cwd();
const libs = path.join(root, "build", "libs");
const skipBuild = process.argv.includes("--no-build");
const skipCopy = process.argv.includes("--no-copy");
const skipPrune = process.argv.includes("--no-prune");
const MIN_GRADLE_JDK = 21;

function run(command, env) {
  const result = spawnSync(command, {
    stdio: "inherit",
    shell: true,
    cwd: root,
    env: { ...process.env, ...env },
  });
  if (result.status !== 0) {
    console.error(`\n"${command}" failed (exit ${result.status ?? result.signal}).`);
    process.exit(result.status ?? 1);
  }
}

function gradleProperty(name) {
  const file = path.join(root, "gradle.properties");
  const text = readFileSync(file, "utf8");
  const match = text.match(new RegExp(`^[ \\t]*${name}[ \\t]*=[ \\t]*(.+?)[ \\t]*$`, "m"));
  if (!match) throw new Error(`Could not find \`${name}\` in ${file}`);
  return match[1];
}

function javaMajor(javaHome) {
  const executable = path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  if (!existsSync(executable)) return null;
  const result = spawnSync(executable, ["-version"], { encoding: "utf8" });
  const match = `${result.stdout ?? ""}${result.stderr ?? ""}`.match(/version "(\d+)(?:\.(\d+))?/);
  if (!match) return null;
  return Number(match[1]) === 1 ? Number(match[2]) : Number(match[1]);
}

function jdkSearchRoots() {
  if (process.platform === "win32") {
    const programFiles = process.env.ProgramFiles ?? "C:\\Program Files";
    return [
      path.join(programFiles, "Java"),
      path.join(programFiles, "Eclipse Adoptium"),
      path.join(programFiles, "Microsoft"),
      path.join(programFiles, "Amazon Corretto"),
      path.join(programFiles, "Zulu"),
    ];
  }
  if (process.platform === "darwin") return ["/Library/Java/JavaVirtualMachines"];
  return ["/usr/lib/jvm", "/usr/java"];
}

function resolveGradleJdk() {
  if (process.env.JAVA_HOME) {
    const major = javaMajor(process.env.JAVA_HOME);
    if (major !== null && major >= MIN_GRADLE_JDK) {
      return { home: process.env.JAVA_HOME, major, source: "JAVA_HOME" };
    }
  }

  const found = [];
  for (const directory of jdkSearchRoots()) {
    let entries;
    try {
      entries = readdirSync(directory);
    } catch {
      continue;
    }
    for (const entry of entries) {
      const home = process.platform === "darwin"
        ? path.join(directory, entry, "Contents", "Home")
        : path.join(directory, entry);
      const major = javaMajor(home);
      if (major !== null && major >= MIN_GRADLE_JDK) {
        found.push({ home, major, source: "auto-detected" });
      }
    }
  }
  found.sort((a, b) => b.major - a.major);
  return found[0] ?? null;
}

function findJar(baseName, version) {
  const wanted = `${baseName}-${version}.jar`;
  let jars;
  try {
    jars = readdirSync(libs).filter((file) => file === wanted);
  } catch {
    throw new Error(`No build output in ${libs} - build first (pnpm build).`);
  }
  if (jars.length === 0) throw new Error(`${wanted} not found in ${libs}. Did the build succeed?`);
  jars.sort((a, b) => statSync(path.join(libs, b)).mtimeMs - statSync(path.join(libs, a)).mtimeMs);
  return path.join(libs, jars[0]);
}

function minecraftDirectory() {
  if (process.platform === "win32") {
    const appData = process.env.APPDATA ?? path.join(homedir(), "AppData", "Roaming");
    return path.join(appData, ".minecraft");
  }
  if (process.platform === "darwin") {
    return path.join(homedir(), "Library", "Application Support", "minecraft");
  }
  return path.join(homedir(), ".minecraft");
}

function modsDirectory(profile) {
  if (process.env.MINECRAFT_MODS) return process.env.MINECRAFT_MODS;
  return path.join(minecraftDirectory(), process.env.MINECRAFT_PROFILE ?? profile, "mods");
}

function pruneOldVersions(destination, baseName, keep) {
  const escaped = baseName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const ours = new RegExp(`^${escaped}-.*\\.jar$`);
  for (const entry of readdirSync(destination)) {
    if (entry === keep || !ours.test(entry)) continue;
    rmSync(path.join(destination, entry));
    console.log(`Removed stale ${entry}`);
  }
}

const baseName = gradleProperty("archives_base_name");
const version = gradleProperty("mod_version");
const profile = `${gradleProperty("minecraft_version")}-fabric`;

if (!skipBuild) {
  const jdk = resolveGradleJdk();
  if (!jdk) {
    console.error(`No JDK ${MIN_GRADLE_JDK}+ found. Install one or set JAVA_HOME.`);
    process.exit(1);
  }
  console.log(`Using JDK ${jdk.major} for Gradle (${jdk.source}: ${jdk.home})`);
  const wrapper = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
  run(`${wrapper} build`, { JAVA_HOME: jdk.home });
}

if (!skipCopy) {
  const jar = findJar(baseName, version);
  const destination = modsDirectory(profile);
  mkdirSync(destination, { recursive: true });
  const name = path.basename(jar);
  if (!skipPrune) pruneOldVersions(destination, baseName, name);
  const target = path.join(destination, name);
  copyFileSync(jar, target);
  console.log(`Copied ${name} -> ${target}`);
}
