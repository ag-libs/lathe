// Stopped by Lathe's own check, not by a NoSuchMethodError from inside the old compiler plugin.
def log = new File(basedir, 'build.log').text
assert log.contains('[lathe] This build is older than Lathe supports')
assert log.contains('maven-compiler-plugin 3.1 (old-compiler) is older than 3.6.2')
assert log.contains('-Dlathe.disabled=true')
assert !log.contains('NoSuchMethodError')
