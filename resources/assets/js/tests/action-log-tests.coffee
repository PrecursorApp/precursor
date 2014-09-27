j = jasmine.getEnv()

j.describe "minimize", =>
  j.beforeEach =>
    @build = new CI.inner.Build({vcs_url: "https://github.com/test-org/test-project", build_num: 1})
    @action_log = new CI.inner.ActionLog({has_output: false}, @build)


  j.it "should be true when an action is successful", =>
    @action_log.status("success")
    @expect(@action_log.minimize()).toBe(true)


  j.it "should be false when an action is running", =>
    @action_log.status("running")
    @expect(@action_log.minimize()).toBe(false)


  j.it "should be false if there are action messages", =>
    @action_log.status("success")
    @action_log.messages.push("an action message")
    @expect(@action_log.minimize()).toBe(false)


  j.it "should be false if an action has failed", =>
    @action_log.status("failed")
    @expect(@action_log.minimize()).toBe(false)


  j.it "should stay at what the user picks", =>
    @action_log.user_minimized(true)
    @action_log.status("failed")
    @expect(@action_log.minimize()).toBe(true)

    @action_log.status("running")
    @expect(@action_log.minimize()).toBe(true)

    @action_log.user_minimized(false)
    @expect(@action_log.minimize()).toBe(false)


j.describe "maybe_retrieve_output", =>
  j.beforeEach =>
    @build = new CI.inner.Build({vcs_url: "https://github.com/test-org/test-project", build_num: 1})
    # Prevent the ActionLog constructor from fetching output with user_minimized: true
    @action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1, user_minimized: true}, @build)

    $.mockjax
      url: "/api/v1/project/test-org/test-project/1/output/*"
      responseText: [{type: "out", message: "This is some stdout\nhi"}]
      responseTime: 50

  j.afterEach =>
    $.mockjaxClear()


  j.it "should run if the action log is expanded", =>
    runs =>
      @action_log.user_minimized(false)
      @action_log.maybe_retrieve_output()
      @expect(@action_log.retrieving_output()).toBe(true)

    waitsFor (=> @action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      @expect(@action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
      @expect(@action_log.retrieved_output()).toBe(true)
      @expect(@action_log.retrieving_output()).toBe(false)


  j.it "should not run if the action is minimized", =>
    @action_log.user_minimized(true)

    @action_log.maybe_retrieve_output()

    @expect(@action_log.final_out()).toEqual([])
    @expect(@action_log.retrieved_output()).toBe(false)


  j.it "should not run if the action has no output", =>
    @action_log.user_minimized(false)
    @action_log.has_output(false)

    @action_log.maybe_retrieve_output()

    @expect(@action_log.final_out()).toEqual([])
    @expect(@action_log.retrieved_output()).toBe(false)


  j.it "should not run if it has already retrieved output", =>
    @action_log.retrieved_output(true)

    @action_log.maybe_retrieve_output()

    @expect(@action_log.final_out()).toEqual([])
    @expect(@action_log.retrieved_output()).toBe(true)


  j.it "should not run if it is retrieving output", =>
    @action_log.retrieving_output(true)

    @action_log.maybe_retrieve_output()

    @expect(@action_log.final_out()).toEqual([])
    @expect(@action_log.retrieved_output()).toBe(false)
    @expect(@action_log.retrieving_output()).toBe(true)


j.describe "maybe_drop_output", =>
  j.beforeEach =>
    $.mockjax
      url: "/api/v1/project/test-org/test-project/1/output/*"
      responseText: [{type: "out", message: "This is some stdout\nhi"}]

    @build = new CI.inner.Build({vcs_url: "https://github.com/test-org/test-project", build_num: 1})
    @action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1}, @build)

    waitsFor (=> @action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

  j.afterEach =>
    $.mockjaxClear()


  j.it "should drop output if minimized", =>
    @expect(@action_log.trailing_out()).toEqual("<span class='brblue'>hi</span>")
    @expect(@action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])

    @action_log.user_minimized(true)
    @action_log.maybe_drop_output()

    @expect(@action_log.trailing_out()).toEqual("")
    @expect(@action_log.final_out()).toEqual([])
    @expect(@action_log.retrieved_output()).toBe(false)


  j.it "should not drop output if expanded", =>
    @expect(@action_log.trailing_out()).toEqual("<span class='brblue'>hi</span>")
    @expect(@action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])

    @action_log.user_minimized(false)
    @action_log.maybe_drop_output()

    @expect(@action_log.trailing_out()).toEqual("<span class='brblue'>hi</span>")
    @expect(@action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
    @expect(@action_log.retrieved_output()).toBe(true)


j.describe "reduced ActionLog memory usage behaviour", =>
  j.beforeEach =>
    @build = new CI.inner.Build({vcs_url: "https://github.com/test-org/test-project", build_num: 1})

    $.mockjax
      url: "/api/v1/project/test-org/test-project/1/output/*"
      responseText: [{type: "out", message: "This is some stdout\nhi"}]
      responseTime: 50

  j.afterEach =>
    $.mockjaxClear()


  j.it "should retrieve output immediately if it is not minimized when constructed", =>
    action_log = null

    runs =>
      action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1}, @build)

    waitsFor (=> action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      @expect(action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
      @expect(action_log.retrieved_output()).toBe(true)
      @expect(action_log.retrieving_output()).toBe(false)


  j.it "should retrieve output if the user expands the action and it has not retrieved output", =>
    action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1, status: "success"}, @build)

    runs =>
      action_log.toggle_minimize()
      @expect(action_log.user_minimized()).toBe(false)

    waitsFor (=> action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      @expect(action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
      @expect(action_log.retrieved_output()).toBe(true)
      @expect(action_log.retrieving_output()).toBe(false)


  j.it "should retrieve output if output has been dropped", =>
    action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1, status: "success"}, @build)

    runs =>
      action_log.toggle_minimize()
      @expect(action_log.user_minimized()).toBe(false)

    waitsFor (=> action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      action_log.toggle_minimize()
      @expect(action_log.user_minimized()).toBe(true)

      action_log.maybe_drop_output()

      action_log.toggle_minimize()
      @expect(action_log.user_minimized()).toBe(false)

    waitsFor (=> action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      @expect(action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
      @expect(action_log.retrieved_output()).toBe(true)
      @expect(action_log.retrieving_output()).toBe(false)


  j.it "should retrieve output when the action fails", =>
    # Prevent the ActionLog constructor from fetching output with user_minimized: true
    action_log = new CI.inner.ActionLog({has_output: true, step: 0, index: 1, user_minimized: true}, @build)
    # But make sure the ActionLog isn't minimized when it fails
    action_log.user_minimized(false)

    @expect(action_log.trailing_out()).toEqual("")
    @expect(action_log.final_out()).toEqual([])

    runs =>
      action_log.status("failed")

    waitsFor (=> action_log.retrieving_output() == false),
             "Retrieving output didn't become false",
             1000

    runs =>
      @expect(action_log.final_out()).toEqual(["<span class='brblue'>This is some stdout\n</span>"])
      @expect(action_log.retrieved_output()).toBe(true)
      @expect(action_log.retrieving_output()).toBe(false)
