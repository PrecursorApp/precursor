CI.inner.Project = class Project extends CI.inner.Obj
  ## A project in the DB
  observables: =>
    setup: null
    dependencies: null
    post_dependencies: null
    test: null
    extra: null
    latest_build: null
    hipchat_room: null
    hipchat_api_token: null
    hipchat_notify: false
    hipchat_notify_prefs: null
    slack_channel: null
    slack_subdomain: null
    slack_api_token: null
    slack_notify_prefs: null
    slack_webhook_url: null
    hall_room_api_token: null
    hall_notify_prefs: null
    campfire_room: null
    campfire_token: null
    campfire_subdomain: null
    campfire_notify_prefs: null
    flowdock_api_token: null
    irc_server: null
    irc_channel: null
    irc_keyword: null
    irc_username: null
    irc_password: null
    irc_notify_prefs: null
    heroku_deploy_user: null
    ssh_keys: []
    followed: null
    loading_github_users: false
    loading_billing: false
    parallel: 1
    focused_parallel: 1
    has_usable_key: true
    retried_build: null
    branches: null
    default_branch: null
    tokens: []
    tokenLabel: ""
    tokenScope: "status"
    env_vars: []
    env_varName: ""
    env_varValue: ""
    show_branch_input: false
    settings_branch: null
    show_test_new_settings: false
    loaded_settings: false
    github_permissions: null
    feature_flags: {}
    checkout_keys: []

  constructor: (json) ->

    super json

    @latest_build(@compute_latest_build())

    CI.inner.VcsUrlMixin(@)

    # Make sure @parallel remains an integer
    @editParallel = @komp
      read: ->
        @parallel()
      write: (val) ->
        @parallel(parseInt(val))
      owner: @

    @build_url = @komp =>
      @vcs_url() + '/build'

    @default_branch.subscribe (val) =>
      @settings_branch(val)

    @has_settings = @komp =>
      @setup() or @dependencies() or @post_dependencies() or @test() or @extra()

    ## Parallelism
    @billing = new CI.inner.Billing
      org_name: @org_name()

    # use safe defaults in case chosenPlan is null
    @plan = @komp =>
      @billing.chosenPlan() || new CI.inner.Plan

    @parallelism_options = @komp =>
      [1..Math.max(@plan().max_parallelism(), 24)]

    # Trial parallelism is counted as paid here
    @paid_parallelism = @komp =>
      Math.min @plan().max_parallelism(), @billing.usable_containers()

    @parallel.subscribe (val) =>
      @focused_parallel(val)

    @parallel_label_style = (num) =>
      disabled: @komp =>
        # weirdly sends num as string when num is same as parallel
        parseInt(num) > @paid_parallelism()
      selected: @komp =>
        parseInt(num) is @parallel()
      bad_choice: @komp =>
        parseInt(num) <= @paid_parallelism() && @billing.usable_containers() % parseInt(num) isnt 0

    @show_upgrade_plan = @komp =>
      @plan().max_parallelism() < @focused_parallel()

    @show_add_containers = @komp =>
      @paid_parallelism() < @focused_parallel() <= @plan().max_parallelism()

    @show_upgrade_trial = @komp =>
      @paid_parallelism() < @focused_parallel()

    @show_uneven_divisor_warning_p = @komp =>
      @focused_parallel() <= @paid_parallelism() && @billing.usable_containers() % @focused_parallel() isnt 0

    @simultaneous_builds = @komp =>
      Math.floor(@billing.usable_containers() / @focused_parallel())

    @show_number_of_simultaneous_builds_p = @komp =>
      @focused_parallel() <= @paid_parallelism()

    ## Sidebar
    @branch_names = @komp =>
      names = (k for own k, v of @branches())
      names.sort()

    @pretty_branch_names = @komp =>
      decodeURIComponent(name) for name in @branch_names()

    @personal_branch_p = (branch_name) =>
      if branch_name is @default_branch()
        true
      else if @branches()[branch_name] and @branches()[branch_name].pusher_logins
        VM.current_user().login in @branches()[branch_name].pusher_logins
      else
        false

    @personal_branches = () =>
      @branch_names().filter (name) =>
        @personal_branch_p(name)

    @show_all_branches = @komp =>
      VM.browser_settings.settings().show_all_branches

    @branches_collapsed = @komp =>
      VM.browser_settings.settings()["#{@project_name()}_branches_collapsed"]

    @branch_names_to_show = @komp =>
      if @branches_collapsed()
        []
      else if @show_all_branches()
        @branch_names()
      else
        @personal_branches()

    @show_all_tooltip = () =>
      if @show_all_branches()
        "Show less branches"
      else
        "Show all branches"

    @sorted_builds = (branch_name) =>
      if @branches()[branch_name]
        recent = @branches()[branch_name].recent_builds or []
        running = @branches()[branch_name].running_builds or []
        recent.concat(running).sort(Project.buildSort)
      else
        []

    @latest_branch_build = (branch_name) =>
      build = @sorted_builds(branch_name)[0]
      if build
        new CI.inner.Build(build)

    @recent_branch_builds = (branch_name) =>
      builds = @sorted_builds(branch_name)[0..4]
      new CI.inner.Build(b) for b in builds

    @build_path = (build_num) =>
      @project_path() + "/" + build_num

    @branch_path = (branch_name) =>
      "#{@project_path()}/tree/#{branch_name}"

    @active_style = (project_name, branch) =>
      if VM.selected().project_name is project_name
        if VM.selected().branch
          if decodeURIComponent(branch) is VM.selected().branch
            {selected: true}
        else if not branch
          {selected: true}

    # Forces the notification preference true/false checkbox value to
    # convert to either smart or null
    @translate_checked = (pref_observable) =>
      ko.computed
        read: () ->
          pref_observable()
        write: (newVal) ->
          if newVal then pref_observable("smart") else pref_observable(null)

    @show_trial_notice = @komp =>
      @billing.existing_plan_loaded() &&
        @billing.trial() &&
          @billing.trial_end() &&
            @billing.trial_days() < 15 # we probably hacked the db here

    @show_enable_project_notice = @komp =>
       !@has_usable_key()

    @show_build_page_trial_notice = @komp =>
      @show_trial_notice() &&
       !@billing.trial_over() &&
         @billing.trial_days() < 4

    # This is inserted as html, so be careful that everything is escaped properly
    @trial_notice_text = @komp =>
      org_name = _.escape(@billing.org_name())
      plan_path = CI.paths.org_settings org_name, 'plan'
      days = @billing.trial_days()
      if @billing.trial_over()
        "#{org_name}'s trial is over. <a href='#{plan_path}'>Add a plan to continue running your builds</a>."
      else if days > 10
        "#{org_name} is in a 2-week trial, enjoy! (or check out <a href='#{plan_path}'>our plans</a>)"
      else if days > 7
        "#{org_name}'s trial has #{days} days left. <a href='#{plan_path}'>Check out our plans</a>."
      else if days > 4
        "#{org_name}'s trial has #{days} days left. <a href='#{plan_path}'>Add a plan</a> to keep running your builds."
      else
        "#{org_name}'s trial expires in #{@billing.pretty_trial_time()}! <a href='#{plan_path}'>Add a plan to keep running your builds</a>."

    # Make the AJAX call for @users only if we really need it.
    github_users_requested = false
    github_users = @observable []
    @github_users = @komp
      deferEvaluation: true
      read: =>
        if not github_users_requested
          github_users_requested = true
          @loading_github_users true
          $.ajax
            type: "GET"
            url: "/api/v1/project/#{@project_name()}/users"
            success: (results) =>
              github_users ((new CI.inner.GithubUser result) for result in results)
              @loading_github_users false
            error: () =>
              github_users null
              @loading_github_users false
        github_users()

    @github_users_not_following = @komp
      deferEvaluation: true
      read: => if @github_users()
        (user for user in @github_users() when not user.following())

  @sidebarSort: (l, r) ->
    if l.followed() and r.followed() and l.latest_build()? and r.latest_build()?
      if l.latest_build().build_num > r.latest_build().build_num then -1 else 1
    else if l.followed() and l.latest_build()?
      -1
    else if r.followed() and r.latest_build()?
      1
    else
      if l.vcs_url().toLowerCase() > r.vcs_url().toLowerCase() then 1 else -1

  @buildTimeSort: (l, r) ->
    if !l.pushed_at and !r.pushed_at
      0
    else if !l.pushed_at
      1
    else if !r.pushed_at
      -1
    else if new Date(l.pushed_at) > new Date(r.pushed_at)
      -1
    else if new Date(l.pushed_at) < new Date(r.pushed_at)
      1
    else
      0

  @buildNumSort: (l, r) ->
    if l.build_num > r.build_num
      -1
    else if l.build_num < r.build_num
      1
    else
      0

  @buildSort: (l, r) ->
    time_sort = Project.buildTimeSort(l, r)
    if time_sort is 0
      Project.buildNumSort(l, r)
    else
      time_sort

  too_much_parallelism_text: () =>
    n = @focused_parallel()
    "You need #{n} containers on your plan to use #{n}x parallelism."

  compute_latest_build: () =>
    if @branches()? and @branches()[@default_branch()] and @branches()[@default_branch()].recent_builds?
      new CI.inner.Build @branches()[@default_branch()].recent_builds[0]

  format_branch_name: (name, len) =>
    decoded_name = decodeURIComponent(name)
    if len?
      CI.stringHelpers.trimMiddle(decoded_name, len)
    else
      decoded_name

  retry_latest_build: =>
    @latest_build().retry_build()

  parallelism_description_style: =>
    'selected-label': @focused_parallel() == @parallel()

  disable_parallel_input: (num) =>
    num > @paid_parallelism()

  maybe_load_billing: =>
    if not @billing.existing_plan_loaded()
      @load_billing()

  load_billing: =>
    @loading_billing(true)
    $.ajax
      type: "GET"
      url: "/api/v1/project/#{@project_name()}/plan"
      success: (result) =>
        @billing.loadPlanData(result)
        @billing.existing_plan_loaded(true)
        @loading_billing(false)

  checkbox_title: =>
    "Add CI to #{@project_name()}"

  unfollow: (data, event) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/project/#{@project_name()}/unfollow"
      success: (data) =>
        @followed(data.followed)
        _gaq.push(['_trackEvent', 'Projects', 'Remove']);
        VM.loadProjects() # refresh sidebar

  follow: (data, event, callback) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/project/#{@project_name()}/follow"
      success: (data) =>
        @followed(data.followed)
        _gaq.push(['_trackEvent', 'Projects', 'Add'])
        if callback? then callback(data)

  enable: (data, event, callback) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/project/#{@project_name()}/enable"
      success: (data) =>
        @has_usable_key(data.has_usable_key)

  follow_and_maybe_visit: (data, event) =>
    callback = (data) =>
      if data.first_build
        VM.visit_local_url data.build_url
      else
        $('html, body').animate({ scrollTop: 0 }, 0);
        @followed(data.followed)
        VM.loadRecentBuilds()
      VM.loadProjects() # refresh sidebar

    @follow(data, event, callback)

  save_hooks: (data, event) =>
    $.ajax
      type: "PUT"
      event: event
      url: "/api/v1/project/#{@project_name()}/settings"
      data: JSON.stringify
        hipchat_room: @hipchat_room()
        hipchat_api_token: @hipchat_api_token()
        hipchat_notify: @hipchat_notify()
        hipchat_notify_prefs: @hipchat_notify_prefs()
        slack_channel: @slack_channel()
        slack_subdomain: @slack_subdomain()
        slack_api_token: @slack_api_token()
        slack_notify_prefs: @slack_notify_prefs()
        slack_webhook_url: @slack_webhook_url()
        hall_room_api_token: @hall_room_api_token()
        hall_notify_prefs: @hall_notify_prefs()
        campfire_room: @campfire_room()
        campfire_token: @campfire_token()
        campfire_subdomain: @campfire_subdomain()
        campfire_notify_prefs: @campfire_notify_prefs()
        flowdock_api_token: @flowdock_api_token()
        irc_server: @irc_server()
        irc_channel: @irc_channel()
        irc_keyword: @irc_keyword()
        irc_username: @irc_username()
        irc_password: @irc_password()
        irc_notify_prefs: @irc_notify_prefs()

    false # dont bubble the event up

  toggle_show_branch_input: (data, event) =>
    @show_branch_input(!@show_branch_input())
    $(event.target).tooltip('hide')
    # hasfocus binding is bad here: closes the form when you click the button
    if @show_branch_input()
      $(event.target).siblings("input").focus()

  save_dependencies: (data, event) =>
    @save_specs data, event, =>
      window.location.hash = "#tests"

  save_specs: (data, event, callback) =>
    $.ajax
      type: "PUT"
      event: event
      url: "/api/v1/project/#{@project_name()}/settings"
      data: JSON.stringify
        setup: @setup()
        dependencies: @dependencies()
        post_dependencies: @post_dependencies()
        test: @test()
        extra: @extra()
      success: () =>
        if callback
          callback.call(data, event)
    false # dont bubble the event up

  create_settings_build: (data, event) =>
    url = "/api/v1/project/#{@project_name()}"
    if not _.isEmpty(@settings_branch())
      url += "/tree/#{encodeURIComponent(@settings_branch())}"
    $.ajax
      type: "POST"
      event: event
      url: url
      success: (data) =>
        VM.visit_local_url data.build_url
    false # dont bubble the event up

  save_and_create_settings_build: (data, event) =>
    @save_specs data, event, @create_settings_build

  set_heroku_deploy_user: (data, event) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/project/#{@project_name()}/heroku-deploy-user"
      success: (result) =>
        true
        @refresh()
    false

  clear_heroku_deploy_user: (data, event) =>
    $.ajax
      type: "DELETE"
      event: event
      url: "/api/v1/project/#{@project_name()}/heroku-deploy-user"
      success: (result) =>
        true
        @refresh()
    false

  save_ssh_key: (data, event) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/project/#{@project_name()}/ssh-key"
      data: JSON.stringify
        hostname: $("#hostname").val()
        public_key: $("#publicKey").val()
        private_key: $("#privateKey").val()
      success: (result) =>
        $("#hostname").val("")
        $("#publicKey").val("")
        $("#privateKey").val("")
        @refresh()
        false
    false

  delete_ssh_key: (data, event) =>
    $.ajax
      type: "DELETE"
      event: event
      url: "/api/v1/project/#{@project_name()}/ssh-key"
      data: JSON.stringify
        fingerprint: data.fingerprint
      success: (result) =>
        @refresh()
        false
    false

  invite_team_members: (users) =>
    to_invite = ({id: user.id(), email: user.email()} for user in users)
    $.ajax
      type: "POST"
      url: "/api/v1/project/#{@project_name()}/users/invite"
      data: JSON.stringify to_invite

  refresh: () =>
    $.getJSON "/api/v1/project/#{@project_name()}/settings", (data) =>
      @updateObservables(data)

  set_parallelism: (data, event) =>
    @focused_parallel(@parallel())
    $.ajax
      type: "PUT"
      event: event
      url: "/api/v1/project/#{@project_name()}/settings"
      data: JSON.stringify
        parallel: @parallel()
      success: (data) =>
        @show_test_new_settings(true)
      error: (data) =>
        @refresh()
    true

  parallel_input_id: (num) =>
    "parallel_input_#{num}"

  parallel_focus_in: (place) =>
    if @focusTimeout? then clearTimeout(@focusTimeout)
    @focused_parallel(place)

  parallel_focus_out: (place) =>
    if @focusTimeout? then clearTimeout(@focusTimeout)
    @focusTimeout = window.setTimeout =>
      @focused_parallel(@parallel())
    , 200

  load_checkout_keys: () =>
    $.getJSON "/api/v1/project/#{@project_name()}/checkout-key", (data) =>
      @checkout_keys(data)

  create_checkout_key: (type, data, event) =>
    $.ajax
      event: event
      type: "POST"
      url: "/api/v1/project/#{@project_name()}/checkout-key",
      data: JSON.stringify
        type: type
      success: (result) =>
        @load_checkout_keys()
    false

  delete_checkout_key: (data, event) =>
    $.ajax
      type: "DELETE"
      url: "/api/v1/project/#{@project_name()}/checkout-key/#{data.fingerprint}",
      success: (result) =>
        @load_checkout_keys()
      false

  checkout_key_link: (checkout_key) =>
    if checkout_key.type == "deploy-key"
      "https://github.com/#{@project_name()}/settings/keys"
    else if checkout_key.type == "github-user-key" and checkout_key.login == VM.current_user().login
      "https://github.com/settings/ssh"
    else
      null

  checkout_key_description: (checkout_key) =>
    if checkout_key.type == "deploy-key"
      "#{@project_name()} deploy key"
    else if checkout_key.type == "github-user-key"
      "#{checkout_key.login} user key"
    else
      null

  load_tokens: () =>
    $.getJSON "/api/v1/project/#{@project_name()}/token", (data) =>
      @tokens(data)

  create_token: (data, event) =>
    $.ajax
      event: event
      type: "POST"
      url: "/api/v1/project/#{@project_name()}/token",
      data: JSON.stringify
        label: @tokenLabel()
        scope: @tokenScope()
      success: (result) =>
        @tokenLabel("")
        @load_tokens()
    false

  delete_token: (data, event) =>
    $.ajax
      type: "DELETE"
      url: "/api/v1/project/#{@project_name()}/token/#{data.token}",
      success: (result) =>
        @load_tokens()
    false

  load_env_vars: () =>
    $.getJSON "/api/v1/project/#{@project_name()}/envvar", (data) =>
      @env_vars(data)

  create_env_var: (data, event) =>
    $.ajax
      event: event
      type: "POST"
      url: "/api/v1/project/#{@project_name()}/envvar",
      data: JSON.stringify
        name: @env_varName()
        value: @env_varValue()
      success: (result) =>
        @env_varName("")
        @env_varValue("")
        @load_env_vars()
      false

  delete_env_var: (data, event) =>
    $.ajax
      type: "DELETE"
      url: "/api/v1/project/#{@project_name()}/envvar/#{data.name}",
      success: (result) =>
        @load_env_vars()
    false

  maybe_load_settings: () =>
    if not @loaded_settings()
      @load_settings()

  load_settings: () =>
    $.getJSON "/api/v1/project/#{@project_name()}/settings", (data) =>
      @updateObservables(data)
      @loaded_settings(true)

  feature_flag: (name, inverted=false) => @komp
    read: => if inverted then !@feature_flags()[name] else !!@feature_flags()[name]
    write: (value) =>
      old = @feature_flags()
      old[name] = if value then not inverted else inverted
      @feature_flags(old)
      # Notify the backend.
      $.ajax
        type: "PUT"
        url: "/api/v1/project/#{@project_name()}/settings"
        data: JSON.stringify
          feature_flags: _.pick(old, name)
        error: (data) =>
          @refresh()

  toggle_branches_collapsed: () =>
    VM.browser_settings.toggle_setting("#{@project_name()}_branches_collapsed")

  branch_status: (branch) =>
      @recent_branch_builds(branch)[0]?.status()
