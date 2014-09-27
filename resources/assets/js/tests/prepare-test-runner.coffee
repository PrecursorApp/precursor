@JasmineReporter = new jasmine.JsApiReporter()
@consoleReporter = new jasmine.ConsoleReporter
  print: (args...) -> console.log.call(console, args...)
jasmine.getEnv().addReporter @JasmineReporter
jasmine.getEnv().addReporter @consoleReporter
