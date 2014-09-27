CI.inner.Obj = class Obj
  constructor: (json={}, defaults={}) ->
    @komps = []

    for k,v of @observables()
      @[k] = @observable(v)

    for k,v of $.extend {}, defaults, json
      if @observables().hasOwnProperty(k) then @[k](v) else @[k] = v

  observables: () => {}

  komps: []

  komp: (args...) =>
    comp = ko.computed args...
    @komps.push(comp)
    comp

  observable: (obj) ->
    if $.isArray obj
      ko.observableArray obj
    else
      ko.observable obj

  updateObservables: (obj) =>
    for k,v of obj
      if @observables().hasOwnProperty(k)
        @[k](v)

  # Meant to be used in a computed observable. Updates every second and returns
  # the number of millis between now and start.
  # It's best to put this behind a conditional, so that it stops evaluating
  # after the duration no longer needs to update.
  updatingDuration: (start) =>
    window.updator()
    moment().diff(start)

  clean: () =>
    for k in @komps
      k.dispose()

CI.inner.VcsUrlMixin = (obj) ->
  obj.vcs_url = ko.observable(if obj.vcs_url then obj.vcs_url else "")

  obj.observables.vcs_url = obj.vcs_url

  obj.project_name = obj.komp ->
    m = obj.vcs_url().match("^https?://[^/]+/(.*)")
    if (m == null) then "" else m[1]

  # slashes aren't allowed in github org/user names or project names
  obj.org_name = obj.komp ->
    obj.project_name().split("/")[0]

  obj.repo_name = obj.komp ->
    obj.project_name().split("/")[1]

  obj.project_display_name = obj.komp ->
    obj.project_name().replace("/", '/\u200b')

  obj.project_path = obj.komp ->
    "/gh/#{obj.project_name()}"

  obj.edit_link = obj.komp () =>
    "#{obj.project_path()}/edit"
