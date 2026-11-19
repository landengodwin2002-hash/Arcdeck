#!/usr/bin/env python3
"""Prepare the pinned upstream project as the independently packaged ArcDeck application."""
import argparse
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_SHA = '5ee271ea9545685417aecdd13b5ace033cdb7806'
VERSION = '1.3.0'


def prepare(project, number):
    project = project.resolve()
    actual = subprocess.check_output(['git','-C',str(project),'rev-parse','HEAD'],text=True).strip()
    if actual != UPSTREAM_SHA:
        raise SystemExit(f'Upstream mismatch: expected {UPSTREAM_SHA}, got {actual}')
    patch = Path(__file__).resolve().parents[1]
    subprocess.run([sys.executable,str(patch/'install_workspace.py'),str(project)],check=True)
    prop = project/'gradle.properties'
    prop.write_text('\n'.join(re.sub(r'-Xmx\d+[mgMG]', '-Xmx4096m', line) for line in prop.read_text().splitlines()
        if not line.startswith('org.gradle.java.home') and 'trustStoreType=Windows-ROOT' not in line)+'\n')
    gradle = project/'app/build.gradle.kts'
    source = gradle.read_text()
    for pattern, replacement in [
        (r'\bapplicationId = "[^"\n]+"', 'applicationId = "dev.arcdeck.workspace"'),
        (r'\bversionName = "[^"\n]+"', f'versionName = "{VERSION}"'),
        (r'\bversionCode = \d+', f'versionCode = {13000+number}'),
    ]:
        source, count = re.subn(pattern,replacement,source,count=1)
        if count!=1: raise SystemExit(f'Expected one build identity anchor: {pattern}')
    marker = '// ArcDeck JVM regression test output'
    if marker not in source:
        source += f'''\n{marker}
tasks.withType<Test>().configureEach {{
    maxParallelForks = 1
    maxHeapSize = "1536m"
    systemProperty("arcdeck.screenshot.dir", rootProject.file("build/reports/arcdeck").absolutePath)
    testLogging {{ events("passed", "skipped", "failed") }}
}}
'''
    gradle.write_text(source)
    for path in project.glob('app/src/main/res/values*/strings.xml'):
        text=path.read_text(encoding='utf-8-sig')
        text=re.sub(r'(<string\s+name="app_name"[^>]*>).*?(</string>)',r'\1ArcDeck Workspace\2',text,flags=re.S)
        path.write_text(text)
    for variant,label in [('debug','ArcDeck Workspace'),('release','ArcDeck Workspace'),('lite','ArcDeck Workspace Lite')]:
        path=project/f'app/src/{variant}/res/values/arcdeck.xml'
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">{label}</string></resources>\n')
    print(f'Prepared ArcDeck {VERSION}, versionCode={13000+number}, applicationId=dev.arcdeck.workspace')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('project',type=Path)
    p.add_argument('--build-number',type=int,default=1)
    a=p.parse_args()
    if not 1<=a.build_number<2_000_000_000: p.error('build number out of range')
    prepare(a.project,a.build_number)
