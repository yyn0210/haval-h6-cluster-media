#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 Android SDK 自带工具链直接构建 APK，不需要 Gradle、不需要联网。

用法:
    python build_apk.py            # 构建 debug APK
    python build_apk.py --clean    # 先清理

依赖:
    - JDK 8+（javac / keytool / jar 在 PATH 里，或用 JAVA_HOME 定位）
    - Android SDK（ANDROID_SDK_ROOT / ANDROID_HOME，或脚本里自动探测）
      需要: platforms/android-28/android.jar
            build-tools/<ver>/ aapt2, d8, zipalign, apksigner

产物: out/haval-h6-cluster-media-v<versionName>.apk
      （版本号从 app/build.gradle 读取，与 Gradle 构建保持一致）
"""
import io
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, 'app', 'src', 'main')
STUBS = os.path.join(ROOT, 'aptiv-stubs', 'src', 'main', 'java')
BUILD = os.path.join(ROOT, 'build')
OUT = os.path.join(ROOT, 'out')
KEYSTORE = os.path.join(ROOT, 'debug.keystore')

PROJECT = 'haval-h6-cluster-media'

# Windows 控制台默认不是 UTF-8，中文日志会乱码
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def log(msg):
    print('[build] ' + msg)


def die(msg):
    print('[build][ERROR] ' + msg)
    sys.exit(1)


def run(cmd, cwd=None):
    """所有外部工具都以 ROOT 为工作目录、只接收相对路径。

    原因：aapt2/d8 等工具解析 Windows 命令行参数时对非 ASCII 绝对路径支持不好，
    路径里含中文（本工程就在中文目录下）时会被判为"找不到文件"。
    """
    shown = ' '.join(cmd) if isinstance(cmd, list) else cmd
    log(shown)
    p = subprocess.run(cmd, cwd=cwd or ROOT, shell=isinstance(cmd, str))
    if p.returncode != 0:
        die('命令失败，退出码 %d' % p.returncode)


def rel(path):
    """转成相对 ROOT 的路径，规避中文绝对路径问题。"""
    return os.path.relpath(path, ROOT)


def find_sdk():
    candidates = [
        os.environ.get('ANDROID_SDK_ROOT'),
        os.environ.get('ANDROID_HOME'),
        r'D:\Android\Sdk',
        r'C:\Android\Sdk',
        os.path.expanduser(r'~\AppData\Local\Android\Sdk'),
    ]
    for c in candidates:
        if c and os.path.isdir(os.path.join(c, 'platforms')):
            return c
    die('找不到 Android SDK，请设置 ANDROID_SDK_ROOT')


def pick_platform(sdk):
    pdir = os.path.join(sdk, 'platforms')
    want = 'android-28'
    if os.path.isdir(os.path.join(pdir, want)):
        return os.path.join(pdir, want, 'android.jar')
    plat = sorted(d for d in os.listdir(pdir) if d.startswith('android-'))
    if not plat:
        die('没有可用的 platform')
    log('android-28 不存在，退回 %s' % plat[-1])
    return os.path.join(pdir, plat[-1], 'android.jar')


def pick_build_tools(sdk):
    """选一个 aapt2/d8/zipalign/apksigner 齐全的最高版本。"""
    bdir = os.path.join(sdk, 'build-tools')

    def has_all(d):
        names = set(os.path.splitext(f)[0].lower() for f in os.listdir(d))
        need = {'aapt2', 'd8', 'zipalign', 'apksigner'}
        return need.issubset(names)

    vers = []
    for d in os.listdir(bdir):
        try:
            key = tuple(int(x) for x in d.split('.'))
        except ValueError:
            continue
        full = os.path.join(bdir, d)
        if os.path.isdir(full) and has_all(full):
            vers.append((key, d))
    if not vers:
        die('没有工具齐全的 build-tools')
    vers.sort()
    return os.path.join(bdir, vers[-1][1])


def tool(bt, name):
    for ext in ('.exe', '.bat', ''):
        p = os.path.join(bt, name + ext)
        if os.path.isfile(p):
            return p
    die('找不到工具 %s' % name)


def javac_path():
    jh = os.environ.get('JAVA_HOME')
    if jh:
        p = os.path.join(jh, 'bin', 'javac.exe')
        if os.path.isfile(p):
            return p
        p = os.path.join(jh, 'bin', 'javac')
        if os.path.isfile(p):
            return p
    return 'javac'


def keytool_path():
    jh = os.environ.get('JAVA_HOME')
    if jh:
        for exe in ('keytool.exe', 'keytool'):
            p = os.path.join(jh, 'bin', exe)
            if os.path.isfile(p):
                return p
    return 'keytool'


def read_version():
    """从 app/build.gradle 读版本，避免脚本与 Gradle 各写一份、版本号漂移。"""
    code, name = 3, '1.2'
    try:
        with io.open(os.path.join(ROOT, 'app', 'build.gradle'), encoding='utf-8') as f:
            text = f.read()
        m = re.search(r'versionCode\s+(\d+)', text)
        if m:
            code = int(m.group(1))
        m = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", text)
        if m:
            name = m.group(1)
    except Exception as e:
        log('读取 app/build.gradle 版本失败，使用内置版本 %d/%s: %s' % (code, name, e))
    return code, name


def collect_java(src_root):
    out = []
    for dirpath, _, files in os.walk(src_root):
        for f in files:
            if f.endswith('.java'):
                out.append(os.path.join(dirpath, f))
    return out


def collect_class(root):
    out = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class'):
                out.append(os.path.join(dirpath, f))
    return out


def ensure_keystore():
    if os.path.isfile(KEYSTORE):
        return
    log('生成调试签名证书 debug.keystore')
    run([keytool_path(), '-genkeypair',
         '-keystore', KEYSTORE,
         '-alias', 'androiddebugkey',
         '-storepass', 'android',
         '-keypass', 'android',
         '-keyalg', 'RSA',
         '-keysize', '2048',
         '-validity', '10000',
         '-dname', 'CN=Android Debug,O=Android,C=US'])


def main():
    if '--clean' in sys.argv and os.path.isdir(BUILD):
        shutil.rmtree(BUILD)

    for d in (BUILD, OUT):
        os.makedirs(d, exist_ok=True)

    sdk = find_sdk()
    android_jar = pick_platform(sdk)
    bt = pick_build_tools(sdk)
    log('SDK       = %s' % sdk)
    log('android   = %s' % android_jar)
    log('tools     = %s' % bt)

    aapt2 = tool(bt, 'aapt2')
    d8 = tool(bt, 'd8')
    zipalign = tool(bt, 'zipalign')
    apksigner = tool(bt, 'apksigner')
    javac = javac_path()

    res_zip = os.path.join(BUILD, 'res.zip')
    gen_dir = os.path.join(BUILD, 'gen')
    base_apk = os.path.join(BUILD, 'base.apk')
    stub_classes = os.path.join(BUILD, 'stub-classes')
    app_classes = os.path.join(BUILD, 'app-classes')
    dex_dir = os.path.join(BUILD, 'dex')
    aligned = os.path.join(BUILD, 'aligned.apk')
    version_code, version_name = read_version()
    final_apk = os.path.join(OUT, '%s-v%s.apk' % (PROJECT, version_name))

    for d in (gen_dir, stub_classes, app_classes, dex_dir):
        os.makedirs(d, exist_ok=True)

    # 1) 编译资源
    run([rel(aapt2), 'compile', '--dir', rel(os.path.join(APP, 'res')), '-o', rel(res_zip)])

    # 2) 链接资源 + 生成 R.java
    #    注意：编译产物 res.zip 作为位置参数传入（主资源）；
    #    -R 是「叠加资源(overlay)」语义，用在这里会报 "does not override"。
    run([rel(aapt2), 'link',
         '-o', rel(base_apk),
         '-I', rel(android_jar),
         '--manifest', rel(os.path.join(APP, 'AndroidManifest.xml')),
         '--java', rel(gen_dir),
         '--min-sdk-version', '28',
         '--target-sdk-version', '28',
         '--version-code', str(version_code),
         '--version-name', version_name,
         rel(res_zip)])

    # 3) 编译 aptiv.core 存根（只用于编译期，不进 dex）
    stub_srcs = [rel(p) for p in collect_java(STUBS)]
    if not stub_srcs:
        die('没有找到 aptiv 存根源码')
    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-nowarn', '-Xlint:-options',
         '-classpath', rel(android_jar),
         '-d', rel(stub_classes)] + stub_srcs)

    # 4) 编译应用源码（含 R.java）
    app_srcs = ([rel(p) for p in collect_java(os.path.join(APP, 'java'))]
                + [rel(p) for p in collect_java(gen_dir)])
    classpath = rel(android_jar) + os.pathsep + rel(stub_classes)
    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-nowarn', '-Xlint:-options',
         '-classpath', classpath,
         '-d', rel(app_classes)] + app_srcs)

    # 5) dx: class -> classes.dex（不含存根）
    classes = [rel(p) for p in collect_class(app_classes)]
    if not classes:
        die('没有编译出 class')
    run([rel(d8), '--lib', rel(android_jar), '--min-api', '28',
         '--output', rel(dex_dir)] + classes)

    dex_file = os.path.join(dex_dir, 'classes.dex')
    if not os.path.isfile(dex_file):
        die('d8 没有产出 classes.dex')

    # 6) 把 classes.dex 塞进 APK
    dexed_apk = os.path.join(BUILD, 'dexed.apk')
    shutil.copyfile(base_apk, dexed_apk)
    with zipfile.ZipFile(dexed_apk, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, 'classes.dex')
    log('已写入 classes.dex (%d bytes)' % os.path.getsize(dex_file))

    # 7) 对齐
    run([rel(zipalign), '-f', '-p', '4', rel(dexed_apk), rel(aligned)])

    # 8) 签名
    ensure_keystore()
    if os.path.isfile(final_apk):
        os.remove(final_apk)
    run([rel(apksigner), 'sign',
         '--ks', rel(KEYSTORE),
         '--ks-pass', 'pass:android',
         '--key-pass', 'pass:android',
         '--ks-key-alias', 'androiddebugkey',
         '--out', rel(final_apk),
         rel(aligned)])

    # 9) 校验
    run([rel(apksigner), 'verify', '--print-certs', rel(final_apk)])

    size = os.path.getsize(final_apk)
    log('构建完成: %s (%d bytes / %.1f KB)' % (final_apk, size, size / 1024.0))
    log('安装: adb install -r "%s"' % final_apk)


if __name__ == '__main__':
    main()
