import os
import subprocess
import zipfile
import shutil

ROOT = r"c:\Rayo"
APP_DIR = os.path.join(ROOT, "RayoApp")
TOOLS_DIR = os.path.join(ROOT, "tools")
BUILD_DIR = os.path.join(APP_DIR, "build")

AAPT2 = os.path.join(TOOLS_DIR, "aapt2.exe")
ANDROID_JAR = os.path.join(TOOLS_DIR, "android.jar")
R8_JAR = os.path.join(TOOLS_DIR, "r8.jar")
UBER_SIGNER = os.path.join(ROOT, "uber-apk-signer.jar")

def run(cmd, desc):
    print(f"=== {desc} ===")
    print(" ".join(cmd))
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.stdout:
        print(res.stdout)
    if res.stderr:
        print(res.stderr)
    if res.returncode != 0:
        raise RuntimeError(f"Step '{desc}' failed with exit code {res.returncode}")

def build():
    if os.path.exists(BUILD_DIR):
        shutil.rmtree(BUILD_DIR)
    os.makedirs(BUILD_DIR, exist_ok=True)

    compiled_res_dir = os.path.join(BUILD_DIR, "compiled_res")
    gen_dir = os.path.join(BUILD_DIR, "gen")
    classes_dir = os.path.join(BUILD_DIR, "classes")
    os.makedirs(compiled_res_dir, exist_ok=True)
    os.makedirs(gen_dir, exist_ok=True)
    os.makedirs(classes_dir, exist_ok=True)

    # 1. Compile resources with aapt2
    res_dir = os.path.join(APP_DIR, "res")
    run([AAPT2, "compile", "--dir", res_dir, "-o", os.path.join(BUILD_DIR, "res.zip")], "AAPT2 Compile Resources")

    # 2. Link resources with aapt2
    manifest = os.path.join(APP_DIR, "AndroidManifest.xml")
    proto_apk = os.path.join(BUILD_DIR, "base.apk")
    run([
        AAPT2, "link",
        "-I", ANDROID_JAR,
        "--manifest", manifest,
        "--java", gen_dir,
        "-o", proto_apk,
        os.path.join(BUILD_DIR, "res.zip")
    ], "AAPT2 Link")

    # 3. Compile Java with javac
    java_files = []
    for root, _, files in os.walk(os.path.join(APP_DIR, "src")):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    for root, _, files in os.walk(gen_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))

    run([
        "javac",
        "-encoding", "UTF-8",
        "-source", "1.8",
        "-target", "1.8",
        "-cp", ANDROID_JAR,
        "-d", classes_dir
    ] + java_files, "Javac Compile")

    # 4. Dex with D8
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    dex_dir = os.path.join(BUILD_DIR, "dex")
    os.makedirs(dex_dir, exist_ok=True)

    run([
        "java", "-cp", R8_JAR,
        "com.android.tools.r8.D8",
        "--min-api", "23",
        "--output", dex_dir,
        "--lib", ANDROID_JAR
    ] + class_files, "D8 Dexing")

    # 5. Add classes.dex into base.apk
    dex_file = os.path.join(dex_dir, "classes.dex")
    with zipfile.ZipFile(proto_apk, "a") as z:
        z.write(dex_file, "classes.dex")
    print("Added classes.dex to base.apk!")

    # 6. Sign & Zipalign with uber-apk-signer
    unsigned_apk = proto_apk
    run([
        "java", "-jar", UBER_SIGNER,
        "--apks", unsigned_apk,
        "--allowResign",
        "-o", ROOT
    ], "Uber APK Signer")

    final_signed = os.path.join(ROOT, "base-aligned-debugSigned.apk")
    target_apk = os.path.join(ROOT, "Rayo_Control.apk")
    target_apk_versioned = os.path.join(ROOT, "Rayo_Control_v1.1.1.apk")
    if os.path.exists(final_signed):
        if os.path.exists(target_apk):
            os.remove(target_apk)
        if os.path.exists(target_apk_versioned):
            os.remove(target_apk_versioned)
        shutil.copy2(final_signed, target_apk)
        os.rename(final_signed, target_apk_versioned)
        idsig = os.path.join(ROOT, "base-aligned-debugSigned.apk.idsig")
        if os.path.exists(idsig):
            os.remove(idsig)
        print(f"SUCCESS! Built new APK: {target_apk_versioned} and {target_apk}")
    else:
        raise RuntimeError("Signed APK not found!")

if __name__ == "__main__":
    build()
