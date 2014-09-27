j = jasmine.getEnv()

j.describe "status_style", =>
  @check_status = (status_style, desired) =>
    expected =
      success: false
      failed: false
      running: false
      canceled: false
      waiting: false

    for key, value of desired
      expected[key] = value

    @expect(status_style).toEqual(expected)

  j.beforeEach =>
    @build = new CI.inner.Build({vcs_url: "https://github.com/test-org/test-project", build_num: 1})

    make_action = () =>
      new CI.inner.ActionLog({has_output: false, status: "running"}, @build)

    @actions = (make_action() for dont_care in _.range(4))
    @container = new CI.inner.Container("Test", 0, @actions, @build)


  j.it "should be waiting if all actions have been successful but the build has not finished", =>
    for action in @actions
      action.status("success")

    @build.stop_time(null)
    check_status(@container.status_style(), {waiting: true})


  j.it "should be successful if all actions have been successful and the build is finished", =>
    for action in @actions
      action.status("success")

    @build.stop_time("2014-01-01T12:00")
    check_status(@container.status_style(), {success: true})


  j.it "should be failed if an action has failed, whether the build is running or not", =>
    for action in @actions
      action.status("running")
    @actions[0].status("failed")

    check_status(@container.status_style(), {failed: true})

    @build.stop_time("2014-01-01T12:00")
    check_status(@container.status_style(), {failed: true})


  j.it "should be canceled if any action has been canceled, whether the build is running or not", =>
    for action in @actions
      action.status("running")
    @actions[0].status("canceled")

    check_status(@container.status_style(), {canceled: true})

    @build.stop_time("2014-01-01T12:00")
    check_status(@container.status_style(), {canceled: true})


  j.it "should assume running if there are no actions in the container", =>
    container = new CI.inner.Container("Test", 0, [], @build)
    check_status(container.status_style(), {running: true})
