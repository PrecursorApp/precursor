CI.inner.User = class User extends CI.inner.Obj
  observables: =>
    admin: false
    organizations: []
    collaboratorAccounts: []
    loadingOrganizations: false
    loadingRepos: false
    loadingUser: false
    # the org we're currently viewing in add-projects
    activeOrganization: null
    # keyed on org/account name
    repos: []
    tokens: []
    tokenLabel: ""
    herokuApiKeyInput: ""
    heroku_api_key: ""
    user_key_fingerprint: ""
    email_provider: ""
    all_emails: []
    selected_email: ""
    basic_email_prefs: "smart"
    plan: null
    parallelism: 1
    gravatar_id: null
    github_id: null
    github_oauth_scopes: []
    repo_filter: ""

  constructor: (json) ->
    super json,
      login: ""

    @environment = window.renderContext.env

    @showEnvironment = @komp =>
      @admin() || (@environment is "staging") || (@environment is "development")

    @environmentColor = @komp =>
      result = {}
      result["env-" + @environment] = true
      result

    @in_trial = @komp =>
      # not @paid and @days_left_in_trial >= 0
      false

    @trial_over = @komp =>
      #not @paid and @days_left_in_trial < 0
      # need to figure "paid" out before we really show this
      false

    @showLoading = @komp =>
      @loadingRepos() or @loadingOrganizations()

    @plan_id = @komp =>
      @plan()

    @collaborator = (login) =>
      @komp =>
        for collaborator in @collaboratorAccounts()
          return collaborator if collaborator.login is login

    @collaboratorsWithout = (login) =>
      @komp =>
        c for c in @collaboratorAccounts() when c.login isnt login

    @gravatar_url = (size=200, force=false) =>
      @komp =>
        if @gravatar_id() and @gravatar_id() isnt ""
          "https://secure.gravatar.com/avatar/#{@gravatar_id()}?s=#{size}"
        else if @login
          "https://identicons.github.com/#{@login}.png"
        else if force
          "https://secure.gravatar.com/avatar/00000000000000000000000000000000?s=#{size}"

    @organizations_plus_user = @komp =>
      user_org =
        login: @login
        org: false
        avatar_url: @gravatar_url()

      _.sortBy @organizations().concat(user_org), 'login'

    @has_public_key_scope = @komp =>
      _.contains(@github_oauth_scopes(), 'admin:public_key')

    @filtered_repos = @komp =>
      current_filter = @repo_filter().toLowerCase()
      current_repos = if @repos then @repos() else []
      current_repos.filter (repo) -> repo.name.toLowerCase().indexOf(current_filter) != -1

    @activity_setting = @komp
      deferEvaluation: true
      read: ->
        if VM.browser_settings.settings().show_all_branches
          "All Branch Activity"
        else
          "Your Branch Activity"
      write: (val) ->
        if val is "All Branch Activity"
          VM.browser_settings.set_setting("show_all_branches", true)
        else
          VM.browser_settings.set_setting("show_all_branches", false)
        val
      owner: @

  missing_scopes: () =>
    user_scopes = ['user', 'user:email']

    missing = []
    if _.isEmpty(_.intersection(@github_oauth_scopes(), ['repo']))
      missing.push('repo')

    # only ask for the user scope we want if they don't have any user scope
    if _.isEmpty(_.intersection(@github_oauth_scopes(), user_scopes))
      missing.push('user:email')

    missing


  load_tokens: () =>
    $.getJSON "/api/v1/user/token", (data) =>
      @tokens(data)

  create_token: (data, event) =>
    $.ajax
      event: event
      type: "POST"
      url: "/api/v1/user/token"
      data: JSON.stringify
        label: @tokenLabel()
      success: (result) =>
        @tokenLabel("")
        @load_tokens()
    false

  delete_token: (data, event) =>
    $.ajax
      type: "DELETE"
      url: "/api/v1/user/token/#{data.token}",
      success: (result) =>
        @load_tokens()
    false

  create_user_key: (data, event) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/user/ssh-key"
      data: JSON.stringify {label: @tokenLabel()}
      success: (result) =>
        @user_key_fingerprint(result.user_key_fingerprint)
        true
    false

  delete_user_key: (data, event) =>
    $.ajax
      type: "DELETE"
      event: event
      url: "/api/v1/user/ssh-key"
      data: JSON.stringify {label: @tokenLabel()}
      success: (result) =>
        @user_key_fingerprint(result.user_key_fingerprint)
        true
    false

  save_heroku_key: (data, event) =>
    $.ajax
      type: "POST"
      event: event
      url: "/api/v1/user/heroku-key"
      data: JSON.stringify {apikey: @herokuApiKeyInput()}
      success: (result) =>
        true
        @heroku_api_key(@herokuApiKeyInput())
        @herokuApiKeyInput("")
    false

  save_preferences: (data, event) =>
    $.ajax
      type: "PUT"
      event: event
      url: "/api/v1/user/save-preferences"
      data: JSON.stringify
        basic_email_prefs: @basic_email_prefs()
        selected_email: @selected_email()
      success: (result) =>
        @updateObservables(result)
    false

  save_basic_email_pref: (data, event) =>
    @basic_email_prefs(event.currentTarget.defaultValue)
    @save_preferences(data, event)
    true

  save_email_address: (data, event) =>
    @selected_email(event.currentTarget.defaultValue)
    @save_preferences(data, event)
    true

  loadOrganizations: () =>
    @loadingOrganizations(true)
    $.getJSON '/api/v1/user/organizations', (data) =>
      @organizations(data)
      @setActiveOrganization(data[0])
      @loadingOrganizations(false)

  loadCollaboratorAccounts: () =>
    @loadingOrganizations(true)
    $.getJSON '/api/v1/user/collaborator-accounts', (data) =>
      @collaboratorAccounts(data)
      @loadingOrganizations(false)

  setActiveOrganization: (org, event) =>
    if org
      @activeOrganization(org.login)
      @loadRepos(org)

  loadRepos: (org) =>
    @loadingRepos(true)
    if org.org
      url = "/api/v1/user/org/#{org.login}/repos"
    else
      url = "/api/v1/user/user/#{org.login}/repos"

    $.getJSON url, (data) =>
      @repos((new CI.inner.Repo r for r in data))
      @loadingRepos(false)

  syncGithub: () =>
    @loadingUser(true)
    $.getJSON '/api/v1/sync-github', (data) =>
      @updateObservables(data)
      @loadingUser(false)

  isPaying: () =>
    @plan?

  toggle_recent_activity: () =>
    VM.browser_settings.set_setting("aside_is_slim", false)
    VM.browser_settings.toggle_setting("recent_activity_visible")

  toggle_aside_expanded: () =>
    new_setting = !VM.browser_settings.settings().aside_is_slim
    VM.browser_settings.set_setting("aside_is_slim", new_setting)
    VM.browser_settings.set_setting("recent_activity_visible", new_setting)
    try
      if new_setting
        mixpanel.track('aside_nav_collapsed')
      else
        mixpanel.track('aside_nav_expanded')
    catch e
      console.error e

  toggle_show_all_branches: () =>
    VM.browser_settings.toggle_setting("show_all_branches")

  toggle_show_admin_panel: () =>
    VM.browser_settings.toggle_setting("show_admin_panel")
