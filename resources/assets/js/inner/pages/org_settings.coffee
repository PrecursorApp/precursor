CI.inner.OrgSettingsPage = class OrgSettingsPage extends CI.inner.Page
  constructor: (properties) ->
    @org_name = null
    super(properties)
    @crumbs = [new CI.inner.OrgCrumb(@org_name),
               new CI.inner.OrgSettingsCrumb(@org_name, {active: true})]

    @title = "Org settings - #{@org_name}"
