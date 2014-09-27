# 1. Don't define a test with null as one of the options
# 2. If you change a test's options, you must also change the test's name
# 3. Record your tests here: https://docs.google.com/a/circleci.com/spreadsheet/ccc?key=0AiVfWAkOq5p2dE1MNEU3Vkw0Rk9RQkJNVXIzWTAzUHc&usp=sharing
#
# You can add overrides, which will set the option if override_p returns true.

exports = this

exports.ab_test_definitions =
  a_is_a: [true, false]
  split_form: [true, false]
  button_copy: [true, false]
