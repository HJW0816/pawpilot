"""Run pure Kotlin behavior checks using the project's cached Kotlin compiler (no daemon)."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent.parent
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
java_home = os.environ.get('PAWPILOT_JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'))

def jar(group, artifact, version):
    return next(p for p in (cache / group / artifact / version).glob('*/*.jar')
                if not p.name.endswith(('-sources.jar', '-javadoc.jar')))

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.0'), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.0'),
            jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
            jar('org.jetbrains', 'annotations', '13.0')]
base = root / 'app/src/main/java/com/estareng/pawpilot'
output = root / 'validation/2026-10-26-route-ui'
output.mkdir(parents=True, exist_ok=True)
sources = [root / 'tests/SdkFeaturesRegression.kt', base / 'location/LocationFix.kt', root / 'tests/ObservedDrivingRegression.kt', root / 'tests/TimeRegression.kt', root / 'tests/TripRegression.kt', root / 'tests/NavigationRegression.kt', root / 'tests/RouteRegression.kt', root / 'tests/RestReviewRegression.kt',
           base / 'navigation/NavigationSearch.kt', base / 'navigation/PetRestStop.kt',
           base / 'navigation/RouteContext.kt', base / 'vehicle/VehicleUx.kt',
           base / 'RestReminder.kt', base / 'reviews/VisitReview.kt',
           base / 'PetComfortEngine.kt', base / 'TripState.kt',
           base / 'vehicle/ObservedDrivingClock.kt', base / 'vehicle/DrivingTimer.kt', base / 'vehicle/VehicleSnapshot.kt']
target = output / 'regressions.jar'
subprocess.run([java_home + '/bin/java', '-cp', os.pathsep.join(map(str, compiler)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-classpath', str(stdlib), '-d', str(target), *map(str, sources)], check=True)
results = []
for entry in ('SdkFeaturesRegressionKt', 'ObservedDrivingRegressionKt', 'TimeRegressionKt', 'TripRegressionKt', 'NavigationRegressionKt', 'RouteRegressionKt', 'RestReviewRegressionKt'):
    result = subprocess.run([java_home + '/bin/java', '-cp', os.pathsep.join((str(target), str(stdlib))), entry],
                            check=True, capture_output=True, text=True)
    print(result.stdout, end='')
    results.append(result.stdout)
(output / 'regression-results.txt').write_text(''.join(results))
