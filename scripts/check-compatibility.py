"""Compile against published CE / Paper APIs; results are compile evidence, not gameplay certification."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CE_VERSIONS = ["26.7", "26.7.1", "26.7.2", "26.7.3", "26.7.4", "26.8", "26.8.1", "26.8.2", "26.9", "26.9.1", "26.10"]
PAPER_VERSIONS = ["1.20-R0.1-SNAPSHOT", "1.20.1-R0.1-SNAPSHOT", "1.20.2-R0.1-SNAPSHOT", "1.20.4-R0.1-SNAPSHOT", "1.20.6-R0.1-SNAPSHOT", "1.21.1-R0.1-SNAPSHOT", "1.21.4-R0.1-SNAPSHOT", "1.21.8-R0.1-SNAPSHOT", "26.1.2.build.74-stable"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ce", nargs="*", default=CE_VERSIONS)
    parser.add_argument("--paper", nargs="*", default=[])
    options = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    results = []
    output = ROOT / "build" / "compatibility"
    output.mkdir(parents=True, exist_ok=True)
    pairs = [("1.20.1-R0.1-SNAPSHOT", ce) for ce in options.ce]
    pairs += [(paper, "26.7") for paper in options.paper]
    for paper, ce in pairs:
        log = output / f"paper-{paper}-ce-{ce}.log"
        command = [str(java), "-classpath", str(ROOT / "gradle/wrapper/gradle-wrapper.jar"),
                   "org.gradle.wrapper.GradleWrapperMain", "compileJava", "--rerun-tasks", "--console=plain",
                   f"-PpaperApiVersion={paper}", f"-PcraftEngineVersion={ce}", "-PtargetJava=17"]
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        passed = result.returncode == 0
        results.append({"paper": paper, "craftengine": ce, "passed": passed, "log": str(log.relative_to(ROOT))})
        print(f"{'PASS' if passed else 'FAIL'} Paper {paper}, CE {ce}", flush=True)
        (output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return 0 if all(value["passed"] for value in results) else 1


if __name__ == "__main__":
    sys.exit(main())
