<!--

title: Test Android applications
last_updated: July 29, 2014

-->

<!--

title: Test Android applications
last_updated: July 29, 2014

-->

CircleCI supports testing Android applications. The SDK is
already installed on the VM at `/usr/local/android-sdk-linux`

To save space, we don't download every android version, so you'll need to specify the versions
you use:

```
dependencies:
  pre:
    - echo y | android update sdk --no-ui --filter "android-18"
```

Note that if you need extended SDK components, such as build-tools, you'll
also need to install those separately. For example:

```
dependencies:
  pre:
    - echo y | android update sdk --no-ui --filter "build-tools-20.0.0"
```

### Caching Android SDK components

Installing SDK components can be expensive if you do it every time, so to speed
up your builds, it is wise to copy
the Android SDK to your home directory and add it to the set of cached directories.
Also, we want to have `ANDROID_HOME` point to this new location.

To accomplish this, put something like the following in your `circle.yml`:

```
machine:
  environment:
    ANDROID_HOME: /home/ubuntu/android
dependencies:
  cache_directories:
    - ~/.android
    - ~/android
  override:
    - ./install-dependencies.sh
```

This references the `install-dependencies.sh`
script. This is a script that you should write that installs
any Android dependencies, creates any test AVDs that you'll need, etc.
It should only do this once, so it should have a check to see
if the cached directories already have all of your dependencies.

For example, here is a basic `install-dependencies.sh`
that installs `android-18`
and some common tools. It also installs the x86 emulator image
and builds an AVD called `testing`.
(Note that if you ever change this file, you should clear your CircleCI
cache.)

```
#!/bin/bash

# Fix the CircleCI path
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$PATH"

DEPS="$ANDROID_HOME/installed-dependencies"

if [ ! -e $DEPS ]; then
  cp -r /usr/local/android-sdk-linux $ANDROID_HOME &&
  echo y | android update sdk -u -a -t android-18 &&
  echo y | android update sdk -u -a -t platform-tools &&
  echo y | android update sdk -u -a -t build-tools-20.0.0 &&
  echo y | android update sdk -u -a -t sys-img-x86-android-18 &&
  echo y | android update sdk -u -a -t addon-google_apis-google-18 &&
  echo n | android create avd -n testing -f -t android-18 &&
  touch $DEPS
fi
```

For your actual tests, the first thing you should do is start up
the emulator, as this usually takes several minutes, sadly.

It's best if you can separate your actual build from installing it and
running tests on the emulator. If at all possible, start the build, and
then you MUST wait for the emulator to finish booting before
installing the APK and running your tests.

To wait for the emulator to boot, you need to wait for the
`init.svc.bootanim` property to be set to `stopped`.

Here's an example script for that, `wait.sh`:

```
#!/bin/bash

export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$PATH"

while true; do
  BOOTUP=$(adb shell getprop init.svc.bootanim | grep -oe '[a-z]\+')
  if [[ "$BOOTUP" = "stopped" ]]; then
    break
  fi

  echo "Got: '$BOOTUP', waiting for 'stopped'"
  sleep 5
done
```

Then, you need to boot up your emulator (in the background), start your build, wait for your
emulator to finish booting, and then run your tests.
In your `circle.yml` file, it will look something like

```
test:
  override:
  - $ANDROID_HOME/tools/emulator -avd testing -no-window -no-boot-anim -no-audio:
      background: true
  - # start your build here
  - ./wait.sh
  - # install your APK
  - # run your tests
```

### Running your tests

The standard way to run tests in the Android emulator is with something like

```
adb logcat &
adb wait-for-device
adb shell am instrument -w com.myapp.test/android.test.InstrumentationTestRunner
```

Unfortunately, this always succeeds, even if the tests fail.
(There's a known bug that `adb shell` doesn't set its exit
code to reflect the command that was run.
See [Android issue 3254](https://code.google.com/p/android/issues/detail?id=3254).)

The only way around this is to parse your test output in a script
and check to see if your tests passed.
For example, if the tests pass, there should be a line that looks like
`OK (15 tests)`.

Here's an example bash script that uses Python to look for that pattern,
and exits with code 0 (success) if the success line is found, and otherwise
with code 1 (error).

```
#!/bin/bash

export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$PATH"

pushd YourTestApp

# clear the logs
adb logcat -c

# run tests and check output
python - << END
import re
import subprocess as sp
import sys
import threading
import time

done = False

def update():
  # prevent CircleCI from killing the process for inactivity
  while not done:
    time.sleep(5)
    print "Running..."

t = threading.Thread(target=update)
t.dameon = True
t.start()

def run():
  sp.Popen(['adb', 'wait-for-device']).communicate()
  p = sp.Popen('adb shell am instrument -w com.myapp.test/android.test.InstrumentationTestRunner',
               shell=True, stdout=sp.PIPE, stderr=sp.PIPE, stdin=sp.PIPE)
  return p.communicate()

success = re.compile(r'OK \(\d+ tests\)')
stdout, stderr = run()

done = True
print stderr
print stdout

if success.search(stderr + stdout):
  sys.exit(0)
else:
  sys.exit(1) # make sure we fail if the test failed
END

RETVAL=$?

# dump the logs
adb logcat -d

popd
exit $RETVAL
```

Please don't hesitate to [contact us](mailto:sayhi@circleci.com)
if you have any questions at all about how to best test Android on
CircleCI.
