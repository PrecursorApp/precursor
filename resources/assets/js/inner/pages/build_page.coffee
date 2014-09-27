CI.inner.BuildPage = class BuildPage extends CI.inner.Page
  constructor: (properties) ->
    @username = null
    @project = null
    @project_name = null
    @build_num = null
    @mention_branch = true
    @show_branch = true

    super(properties)
    @name = "build"
    @project_name = "#{@username}/#{@project}"
    @title = "##{@build_num} - #{@project_name}"

    @crumbs = [new CI.inner.OrgCrumb(@username),
               new CI.inner.ProjectCrumb(@username, @project),
               new CI.inner.ProjectBranchCrumb(@username, @project, ko.computed =>
                 if VM.build()?
                   VM.build().branch())
               new CI.inner.BuildCrumb(@username, @project, @build_num, {active: true})]

    @settings_link = CI.paths.project_settings_path(@username, @project)

    @settings_text = 'Project Settings'

    @favicon_color = @komp =>
      if VM.build()?
        VM.build().favicon_color()

  refresh: =>
    if VM.build() and VM.build().usage_queue_visible()
      VM.build().load_usage_queue_why()
