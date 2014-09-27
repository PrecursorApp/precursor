CI.inner.Org = class Org extends CI.inner.Obj
  observables: =>
    name: null
    projects: []
    users: []
    paid: false
    plan: null
    subpage: 'projects'
    billing: null
    unauthorized: null

  clean: () ->
    super
    VM.cleanObjs(@project_objs())

  constructor: (json) ->

    super json

    @billing new CI.inner.Billing
      current_org_name: @name()

    @loaded = @komp =>
      @unauthorized() || @billing().loaded()

    # projects that have been turned into Project objects
    @project_objs = @komp =>
      for project in @projects()
        project.follower_logins = (u.login for u in project.followers)
        project.followers = _(new CI.inner.User(u) for u in project.followers)
          .sortBy "login"
        new CI.inner.Project(project)

    # users that have been turned into User objects
    @user_objs = @komp =>
      users = for user in @users()
        user.projects = _.chain(@project_objs())
          .filter((p) -> user.login in p.follower_logins)
          .sortBy((p) -> p.repo_name())
          .value()

        new CI.inner.User(user)

      _.sortBy users, (u) -> -1 * u.projects.length

    @projects_with_followers = @komp =>
      _.chain(@project_objs())
        .filter((p) -> p.followers.length)
        .sortBy((p) -> -1 * p.followers.length)
        .value()

    @projects_without_followers = @komp =>
      _.chain(@project_objs())
        .reject((p) -> p.followers.length)
        .sortBy((p) -> p.repo_name())
        .value()

    @can_edit_plan = @komp =>
      @billing().paid() && not @billing().piggieback_plan_p()

    @subpage.subscribe (new_val) =>
      if new_val
        window.location.hash = new_val.replace('_', '-')

    @billing_subpages = ['plan', 'containers', 'organizations', 'billing']

    # This is a computed observable that only exists for its side-effects
    @redirect_to_plan = ko.computed
      read: () =>
        if @billing().loaded() && _.contains(@billing_subpages, @subpage())
          if !@billing().can_edit_plan()
            @subpage('plan')
          else if @subpage() is 'plan'
            @subpage('containers')
      deferEvaluation: false # insurance in case we make true the default

  loadSettings: () =>
    $.ajax
      type: "GET"
      url: "/api/v1/organization/#{@name()}/settings"
      success: (data) =>
        @updateObservables(data)
        @billing().load()
      error: (data) =>
        if data.status is 404
          @unauthorized(true)

  followProjectHandler: (project) =>
    callback = (data) =>
      VM.loadOrgSettings(@name())
    (data, event) => project.follow(data, event, callback)
