CI.inner.Build = class Build extends CI.inner.Obj
  observables: =>
    messages: []
    build_time_millis: null
    all_commit_details: []
    committer_name: null
    committer_email: null
    committer_date: null
    author_name: null
    author_email: null
    author_date: null
    body: null
    start_time: null
    stop_time: null
    queued_at: null
    steps: []
    containers: []
    current_container: null
    status: null
    lifecycle: null
    outcome: null
    failed: null
    infrastructure_fail: null
    dont_build: null
    name: null
    branch: "unknown"
    previous: null
    previous_successful_build: null
    retry_of: null
    subject: null
    parallel: null
    usage_queued_at: null
    usage_queue_why: null
    usage_queue_visible: false
    has_artifacts: false
    artifacts: null
    artifacts_visible: false
    pusher_subscribed: false
    ssh_enabled: false
    rest_commits_visible: false
    node: []
    feature_flags: {}
    dismiss_first_green_build_invitations: false
    compare: null
    is_first_green_build: null
    circle_yml: null
    dismiss_config_diagnostics: false

  clean: () =>
    # pusher fills the console with errors if you unsubscribe
    # from a channel you weren't subscribed to
    if @pusher_subscribed() then VM.pusher.unsubscribe(@pusherChannel())

    super
    VM.cleanObjs(@steps())
    VM.cleanObjs(@containers())
    @clean_usage_queue_why()

  constructor: (json) ->
    steps = json.steps or []

    super(json)

    CI.inner.VcsUrlMixin(@)

    @steps(new CI.inner.Step(s, @) for s in steps)

    @url = @komp =>
      @urlForBuildNum @build_num

    @parallelism_url = @komp =>
     "#{@project_path()}/edit#parallel-builds"

    @important_style = @komp =>
      switch @status()
        when "failed"
          true
        when "timedout"
          true
        when "no_tests"
          true
        else
          false

    @warning_style = @komp =>
      switch @status()
        when "infrastructure_fail"
          true
        when "killed"
          true
        when "not_run"
          true
        else
          false

    @success_style = @komp =>
      switch @outcome()
        when "success"
          true
        else
          false

    @info_style = @komp =>
      switch @status()
        when "running"
          true
        else
          false

    @no_style = @komp =>
      switch @status()
        when "queued"
          true
        when "not_running"
          true
        when "scheduled"
          true
        when "retried"
          true
        else
          false

    @style =
      "fail": @important_style
      "stop": @warning_style
      "pass": @success_style
      "busy": @info_style

    @favicon_color = @komp =>
      if @important_style()
        'red'
      else if @warning_style()
        'orange'
      else if @success_style()
        'green'
      else if @info_style()
        'blue'
      else if @no_style()
        'grey'

    @canceled = @komp =>
      @status() == 'canceled'

    @queued = @komp =>
      @status() == 'queued'

    @scheduled = @komp =>
      @status() == 'scheduled'

    @not_run = @komp =>
      @status() == 'not_run'

    @not_running = @komp =>
      @status() == 'not_running'

    @circle_bug = @komp =>
      @status() == 'infrastructure_fail'

    @finished = @komp =>
      @stop_time()? or @canceled()

    @status_icon_aside = @komp =>
      switch @status()
        when "running"
          "busy-light"
        when "success", "fixed"
          "pass-light"
        when "failed", "canceled", "timedout"
          "fail-light"
        when "queued", "not_running", "retried", "scheduled"
          "hold-light"
        when "no_tests", "not_run", "infrastructure_fail", "killed"
          "stop-light"
        else
          "none-light"

    @status_icon_class =
      "fa-check": @success_style
      "fa-times": @komp => @important_style() || @warning_style() || @canceled()
      "fa-clock-o": @komp => @queued() || @not_running()
      "fa-refresh": @komp => @info_style()
      "fa-calendar-o": @komp => @scheduled()
      "fa-ban": @komp => @not_run() || @circle_bug()

    @status_words = @komp => switch @status()
      when "infrastructure_fail"
        "circle bug"
      when "timedout"
        "timed out"
      when "no_tests"
        "no tests"
      when "not_run"
        "not run"
      when "not_running"
        "not running"
      else
        @status()

    @why_in_words = @komp =>
      switch @why
        when "github"
          "GitHub push by #{@user.login}"
        when "edit"
          "Edit of the project settings"
        when "first-build"
          "First build"
        when "retry"
          "Manual retry of build #{@retry_of()}"
        when "ssh"
          "Retry of build #{@retry_of()}, with SSH enabled"
        when "auto-retry"
          "Auto-retry of build #{@retry_of()}"
        when "trigger"
          if @user
            "#{@user} on CircleCI.com"
          else
            "CircleCI.com"
        else
          if @job_name?
            @job_name
          else
            "unknown"

    @ssh_enabled_now = @komp =>
      # ssh_enabled is undefined before Enabled SSH is completed
      @ssh_enabled() and @node() and _.every(@node(), (n) -> n.ssh_enabled != false)

    @can_cancel = @komp =>
      if @status() == "canceled"
        false
      else
        switch @lifecycle()
          when "not_running"
            true
          when "running"
            true
          when "queued"
            true
          when "scheduled"
            true
          else
            false

    @pretty_start_time = @komp =>
      if @start_time()
        window.updator() # update every second
        CI.time.as_time_since(@start_time())

    @start_time_stamp = @komp =>
      if @start_time()
        window.updator() # update every second
        CI.time.as_timestamp(@start_time())

    @previous_build = @komp =>
      @previous()? and @previous().build_num

    @duration = @komp () =>
      if @start_time() and @stop_time()
        CI.time.as_duration(moment(@stop_time()).diff(moment(@start_time())))
      else
        if @status() == "canceled"
          # build was canceled from the queue
          "canceled"
        else if @start_time()
          duration_millis = @updatingDuration(@start_time())
          CI.time.as_duration(duration_millis) + @estimated_time(duration_millis)

    # don't try to show queue information if the build is pre-usage_queue
    @show_queued_p = @komp =>
      @usage_queued_at()?

    @usage_queued = @komp =>
      not @finished() and not @queued_at()?

    @run_queued = @komp =>
      not @finished() and @queued_at()? and not @start_time()?

    @run_queued_time = @komp =>
      if @start_time() and @queued_at()
        moment(@start_time()).diff(@queued_at())
      else if @queued_at() and @stop_time() # canceled before left queue
        moment(@stop_time()).diff(@queued_at())
      else if @queued_at()
        @updatingDuration(@queued_at())

    @usage_queued_time = @komp =>
      if @usage_queued_at() and @queued_at()
        moment(@queued_at()).diff(@usage_queued_at())
      else if @usage_queued_at() and @stop_time() # canceled before left queue
        moment(@stop_time()).diff(@usage_queued_at())
      else if @usage_queued_at()
        @updatingDuration(@usage_queued_at())

    @queued_time = @komp =>
      (@run_queued_time() || 0) + (@usage_queued_time() || 0)

    @queued_time_summary = @komp =>
      time = CI.time.as_duration
      use = @usage_queued_time()
      run = @run_queued_time()
      if run
        if use < 1000
          time(run + use)
        else
          "#{time(use)} waiting, #{time(run)} queued"
      else
        "#{time(use)} waiting for builds to finish"

    @branch_in_words = @komp =>
      return "unknown" unless @branch()
      @branch().replace(/^remotes\/origin\//, "")

    @trimmed_branch_in_words = @komp =>
      CI.stringHelpers.trimMiddle(@branch_in_words(), 23)

    @github_url = @komp =>
      return unless @vcs_revision
      "#{@vcs_url()}/commit/#{@vcs_revision}"

    @branch_url = @komp =>
      return unless @branch
      "#{@project_path()}/tree/#{@branch()}"

    @github_revision = @komp =>
      return unless @vcs_revision
      @vcs_revision.substring 0, 7

    @author = @komp =>
      @author_name() or @author_email()

    @committer = @komp =>
      @committer_name() or @committer_email()

    @committer_mailto = @komp =>
      if @committer_email()
        "mailto:#{@committer_email}"

    @author_mailto = @komp =>
      if @author_email()
        "mailto:#{@author_email()}"

    @author_isnt_committer = @komp =>
      (@committer_email() isnt @author_email()) or (@committer_name() isnt @author_name())

    @linkified_commits = @komp =>
      linkified = (commit) =>
        lc = $.extend({}, commit)  # make a shallow copy, for purity's sake
        lc.subject = CI.stringHelpers.linkify _.escape(lc.subject), @project_name()
        lc

      (linkified commit for commit in @all_commit_details())

    @head_commits = @komp =>
      # careful not to modify the all_commit_details array here
      @linkified_commits().slice(-3).reverse()

    @rest_commits = @komp =>
      # careful not to modify the all_commit_details array here
      @linkified_commits().slice(0,-3).reverse()

    @tooltip_title = @komp =>
      @status_words() + ": " + @build_num

    # Autoscrolling. A waypoint is set at the bottom of the build page which
    # changes @autoscroll based on the scroll direction. Scrolling to the
    # bottom of the page enables, scrolling up disables.
    @autoscroll = false

    @parallelism = @parallel() + 'x'

    @parallelism_title = @komp =>
      'This build used ' + @parallel() + ' containers. Click here to change parallelism for future builds.'

    # Containers use @finished() to determine their status. Create the
    # Container instances *after* the Build komps are created or container
    # status can be reported incorrectly.
    if @feature_enabled("build_GH1157_container_oriented_ui")
      # _.zip transposes the steps to containers. The non-parallel action
      # references need to be duplicated n times where n is the number of
      # containers
      new_steps = []
      for step in @steps()
        # FIXME This will do for now, but a better check is that the actions in
        # the step have parallel: true
        if step.has_multiple_actions
          new_steps.push(step.actions())
        else
          new_steps.push(_.times @parallel(), -> step.actions()[0])

      containers = _.zip(new_steps...)

      @containers(new CI.inner.Container("C" + index, index, action_list, @) for action_list, index in containers)

      if @containers()[0]?
        @current_container(@containers()[0])
        @current_container().select()

    @display_first_green_build_invitations = @komp =>
      not @dismiss_first_green_build_invitations() and @is_first_green_build()

    saw_invitations_prompt = false
    @first_green_build_invitations = @komp
      deferEvaluation: true
      read: =>
        if VM.project().github_users_not_following()
          if not saw_invitations_prompt
            saw_invitations_prompt = true
            mixpanel.track "Saw invitations prompt",
              first_green_build: true
              project: VM.project().project_name()
          new CI.inner.Invitations VM.project().github_users_not_following(), (sending, users) =>
            node = $ ".first-green"
            node.addClass "animation-fadeout-collapse"
            if sending
              node.addClass "success"
              mixpanel.track "Sent invitations",
                first_green_build: true
                project: VM.project().project_name()
                users: user.login() for user in users
              for user in users
                mixpanel.track "Sent invitation",
                  first_green_build: true
                  project: VM.project().project_name()
                  login: user.login()
                  id: user.id()
                  email: user.email()
              VM.project().invite_team_members users
            window.setTimeout (=> @dismiss_first_green_build_invitations true), 2000

    @config_diagnostics = @komp
      deferEvaluation: true
      read: =>
        if @circle_yml() and not @dismiss_config_diagnostics()
          new CI.inner.Diagnostics @circle_yml().string, @circle_yml().errors

  feature_enabled: (feature_name) =>
    @feature_flags()[feature_name]

   # hack - how can an action know its type is different from the previous, when
   # it doesn't even have access to the build
  different_type: (action) =>
    if @feature_enabled("build_GH1157_container_oriented_ui")
      last = null
      breakLoop = false
      for c in @containers()
        for a in c.actions()
          if a == action
            breakLoop = true # no nested breaks in CS
            break
          last = a
        if breakLoop
          break

      last? and not (last.type() == action.type())
    else
      last = null
      breakLoop = false
      for s in @steps()
        for a in s.actions()
          if a == action
            breakLoop = true # no nested breaks in CS
            break
          last = a
        if breakLoop
          break

      last? and not (last.type() == action.type())

  estimated_time: (current_build_millis) =>
    valid = (estimated_millis) ->
      estimate_is_not_too_low = current_build_millis < estimated_millis * 1.5
      estimate_is_positive = estimated_millis > 0

      return estimate_is_positive and estimate_is_not_too_low

    if @previous_successful_build()?
      estimated_millis = @previous_successful_build().build_time_millis

      if valid estimated_millis
        return "/~" + CI.time.as_estimated_duration(estimated_millis)
    ""

  urlForBuildNum: (num) =>
    "#{@project_path()}/#{num}"

  invite_user: (data, event) =>
    $.ajax
      url: "/api/v1/account/invite"
      type: "POST"
      event: event
      data: JSON.stringify
        invitee: @user
        vcs_url: @vcs_url()
        build_num: @build_num
    event.stopPropagation()

  visit: () =>
    SammyApp.setLocation @url()

  isRunning: () =>
    @start_time() and not @stop_time()

  shouldSubscribe: () =>
    @isRunning() or @status() == "queued" or @status() == "scheduled"

  maybeSubscribe: () =>
    if @shouldSubscribe()
      @pusher_subscribed(true)
      @build_channel = VM.pusher.subscribe(@pusherChannel())
      @build_channel.bind 'pusher:subscription_error', (status) ->
        _rollbar.push status

      @build_channel.bind('newAction', @newAction)
      @build_channel.bind('updateAction', @updateAction)
      @build_channel.bind('appendAction', @appendAction)
      @build_channel.bind('updateObservables', @updateObservables)
      @build_channel.bind('maybeAddMessages', @maybeAddMessages)

  maybeSubscribeObservables: () =>
    if @shouldSubscribe()
      @pusher_subscribed(true)
      @build_channel = VM.pusher.subscribe(@pusherChannel())
      @build_channel.bind 'pusher:subscription_error', (status) ->
        _rollbar.push status
      @build_channel.bind('updateObservables', @updateObservables)

  fillActions: (step, index) =>
    if @feature_enabled("build_GH1157_container_oriented_ui")
      # Fills up @containers and their actions so the step and index are valid
      # 'step' is the position in the container's actions array
      # 'index' is the container index

      # Add at least enough containers to store the actions
      for i in [0..index]
        if not @containers()[i]?
          @containers.setIndex(i, new CI.inner.Container("C" + i, i, [], @))

      # It's possible no containers existed when the build was first loaded, if
      # so, select the first
      if not @current_container()?
        @select_container(@containers()[0])

      # actions can arrive out of order when doing parallel. Fill up the other indices so knockout doesn't bitch
      for i in [0..step]
        if not @containers()[index].actions()[i]?
          @containers()[index].actions.setIndex(i, new CI.inner.ActionLog({}, @))
    else
      # fills up steps and actions such that step and index are valid
      for i in [0..step]
        if not @steps()[i]?
          @steps.setIndex(i, new CI.inner.Step({}))

      # actions can arrive out of order when doing parallel. Fill up the other indices so knockout doesn't bitch
      for i in [0..index]
        if not @steps()[step].actions()[i]?
          @steps()[step].actions.setIndex(i, new CI.inner.ActionLog({}, @))

  newAction: (json) =>
    if @feature_enabled("build_GH1157_container_oriented_ui")
      action_log = new CI.inner.ActionLog(json.log, @)

      if json.log.parallel
        @newParallelAction(json.step, json.index, action_log)
      else
        @newNonParallelAction(json.step, json.index, action_log)

      if @current_container().container_id == @containers()[json.index].container_id
        action_log.subscribe_watcher(@)
    else
      @fillActions(json.step, json.index)
      if old = @steps()[json.step].actions()[json.index]
        old.clean()
      @steps()[json.step].actions.setIndex(json.index, new CI.inner.ActionLog(json.log, @))

  newParallelAction: (step, index, action_log) =>
    @fillActions(step, index)
    if old = @containers()[index].actions()[step]
      old.clean()
    @containers()[index].actions.setIndex(step, action_log)

  newNonParallelAction: (step, index, action_log) =>
    # Create a single action log and add it to *all* containers
    max_index = @containers().length - 1
    @fillActions(step, max_index)

    for container in @containers()
      if old = container.actions()[step]
        old.clean()
      container.actions.setIndex(step, action_log)

  updateAction: (json) =>
    if @feature_enabled("build_GH1157_container_oriented_ui")
      # updates the observables on the action, such as end time and status.
      @fillActions(json.step, json.index)
      @containers()[json.index].actions()[json.step].updateObservables(json.log)
    else
      # updates the observables on the action, such as end time and status.
      @fillActions(json.step, json.index)
      @steps()[json.step].actions()[json.index].updateObservables(json.log)

  appendAction: (json) =>
    if @feature_enabled("build_GH1157_container_oriented_ui")
      # adds output to the action
      @fillActions(json.step, json.index)

      action = @containers()[json.index].actions()[json.step]

      # Only append the output if it's for the current container, otherwise
      # just mark the action has having output
      if @current_container().container_index == json.index
        action.append_output([json.out])
      else
        action.has_output(true)
    else
      # adds output to the action
      @fillActions(json.step, json.index)
      @steps()[json.step].actions()[json.index].append_output([json.out])

  maybeAddMessages: (json) =>
    existing = (message.message for message in @messages())
    (@messages.push(msg) if msg.message not in existing) for msg in json

  trackRetryBuild: (data, clearCache, SSH) =>
    mixpanel.track("Trigger Build",
      "vcs-url": data.vcs_url.substring(19)
      "build-num": data.build_num
      "retry?": true
      "clear-cache?": clearCache
      "ssh?": SSH)

  retry_build: (data, event, clearCache) =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/#{@build_num}/retry"
      type: "POST"
      event: event
      data: JSON.stringify
        "no-cache": clearCache
      success: (data) =>
        console.log("retry build data", data)
        console.log("retry event", event)
        VM.visit_local_url data.build_url
        @trackRetryBuild data, clearCache, false
    false

  clear_cache_retry: (data, event) =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/build-cache"
      type: "DELETE"
      event: event
      success: (data) =>
        @retry_build data, event, true
    false

  retry_build_no_cache: (data, event) =>
    @retry_build data, event, true

  ssh_build: (data, event) =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/#{@build_num}/ssh"
      type: "POST"
      event: event
      success: (data) =>
        VM.visit_local_url data.build_url
        @trackRetryBuild data, false, true
    false

  cancel_build: (data, event) =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/#{@build_num}/cancel"
      type: "POST"
      event: event
    false

  toggle_queue: () =>
    if @usage_queue_visible()
      @usage_queue_visible(!@usage_queue_visible())
      @clean_usage_queue_why()
      @usage_queue_why(null)
    else
      @load_usage_queue_why()
      @usage_queue_visible(true)

  toggle_rest_commits: () =>
    @rest_commits_visible(!@rest_commits_visible())

  clean_usage_queue_why: () =>
    if @usage_queue_why()
      VM.cleanObjs(@usage_queue_why())

  load_usage_queue_why: () =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/#{@build_num}/usage-queue"
      type: "GET"
      success: (data) =>
        @clean_usage_queue_why()
        @usage_queue_why(new CI.inner.Build(build_data) for build_data in data.reverse())
      complete: () =>
        # stop the spinner if there was an error
        @usage_queue_why([]) if not @usage_queue_why()
        _.each(@usage_queue_why(), ((b) -> b.maybeSubscribeObservables()))

  toggle_artifacts: () =>
    if @artifacts_visible()
      @artifacts_visible(!@artifacts_visible())
      @artifacts(null)
    else
      @load_artifacts()
      @artifacts_visible(true)

  clean_artifacts: () =>
    @artifacts(null)

  load_artifacts: () =>
    $.ajax
      url: "/api/v1/project/#{@project_name()}/#{@build_num}/artifacts"
      type: "GET"
      success: (data) =>
        @clean_artifacts()
        data = for artifact in data
                 artifact.pretty_path = artifact.pretty_path.replace "$CIRCLE_ARTIFACTS/", ""
                 artifact.pretty_path = CI.stringHelpers.trimMiddle artifact.pretty_path, 80
                 artifact
        @artifacts(data)
      complete: () =>
        # stop the spinner if there was an error
        @artifacts([]) if not @artifacts()

  report_build: () =>
    VM.raiseIntercomDialog('I think I found a bug in Circle at ' + window.location + '\n\n')

  description: (include_project) =>
    return unless @build_num?

    if include_project
      "#{@project_name()} ##{@build_num}"
    else
      @build_num

  pusherChannel: () =>
    "private-#{@project_name()}@#{@build_num}".replace(/\//g,"@")

  update: (json) =>
    @status(json.status)

  ssh_connection_string: (node) =>
    str = "ssh "
    [port, username, ip] = [node.port, node.username, node.public_ip_addr || node.ip_addr]
    if port
      str += "-p #{port} "
    if username
      str += "#{username}@#{ip}"
    str

  select_container: (container, event) =>
    # Multiple mouse clicks cause the transition to get out of sync with the
    # selected container, ignore double clicks etc.
    # http://www.w3.org/TR/DOM-Level-3-Events/#event-type-click
    if event?.originalEvent instanceof MouseEvent and event?.originalEvent?.detail != 1
      return

    container.select()
    @current_container()?.deselect()

    @current_container(container)
    @switch_container_viewport(@current_container())

  switch_container_viewport: (container, duration=250) =>
    $container_parent = $("#container_parent")
    $element = container.jquery_element()

    # .offset().left measures from the left of the browser window
    parent_offset = $container_parent.offset().left
    offset_delta = $element.offset().left - parent_offset
    scroll_offset = $container_parent.scrollLeft() + offset_delta

    scroll_handler = @handle_browser_scroll

    $container_parent.off("scroll")
    $container_parent.stop().animate({scrollLeft: scroll_offset}, duration)
    $container_parent.queue( (next) ->
                                enable_scroll_handler = () ->
                                    $container_parent.on("scroll", scroll_handler)
                                # There is a race between the final scroll
                                # event and the scroll handler being re-enabled
                                # which causes the last scroll event to be
                                # delivered to the scroll handler after the
                                # animation has finished and the scroll handler
                                # has been re-enabled.
                                # FIXME This slight delay in re-enabling the
                                # handler avoids that but I would much prefer a
                                # solution that isn't time-based
                                setTimeout(enable_scroll_handler, 100)
                                next())

  realign_container_viewport: () =>
    @switch_container_viewport(@current_container(), 0)

  handle_browser_scroll: (event) =>
    # Fix-up viewports after a scroll causes them to be mis-aligned.
    #
    # scrollLeft() is how far the div has scrolled horizontally, divide by the
    # width and round to find out which container most occupies the visible
    # area.
    # FIXME This makes the assumption that the browser will try and centre the
    # selected text, and that centering the text will scroll the
    # container_parent a little more into the new container.
    # Chrome does this. Firefox doesn't
    $container_parent = $("#container_parent")
    container_index = Math.round($container_parent.scrollLeft() / $container_parent.width())
    @select_container(@containers()[container_index])

  enable_autoscroll: (direction) =>
    # Autoscrolling on a finished build would be a horrible user experience.
    @autoscroll = direction is "down" and not @finished()

  height_changed: () =>
    requestAnimationFrame (timestamp) =>
      @maybe_scroll()
      @refresh_waypoints()

  maybe_scroll: () =>
    if @autoscroll
      CI.Browser.scroll_to("bottom")

  refresh_waypoints: () =>
    # Prevent accidentally toggling the autoscroll waypoint while they're being
    # refreshed
    $autoscroll_trigger = $('.autoscroll-trigger')
    $autoscroll_trigger.waypoint("disable")

    $.waypoints("refresh")

    $autoscroll_trigger.waypoint("enable")

  subscription_callback: () =>
    @height_changed()
