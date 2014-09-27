CI.inner.ProjectPage = class ProjectPage extends CI.inner.DashboardPage
  constructor: (properties) ->
    @username = null
    @project = null

    super(properties)

    @crumbs = [new CI.inner.OrgCrumb(@username),
               new CI.inner.ProjectCrumb(@username, @project, {active: true})]

    @settings_link = CI.paths.project_settings_path(@username, @project)

    @settings_text = 'Project Settings'

    @project_name = "#{@username}/#{@project}"
    @title = "#{@username}/#{@project}"

    @show_branch = true

  refresh: () ->
    page = @page_number()
    if page is 0
      VM.loadProject(@username, @project, null, page, true)
