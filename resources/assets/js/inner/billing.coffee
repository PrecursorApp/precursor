CI.inner.Invoice = class Invoice extends CI.inner.Obj
  total: =>
    "$#{@amount_due / 100}"

  zeroize: (val) =>
    if val < 10
      "0" + val
    else
      val

  as_string: (timestamp) =>
    m = moment.unix(timestamp).utc()
    "#{m.year()}/#{@zeroize(m.month()+1)}/#{@zeroize(m.date())}"

  time_period: =>
    "#{@as_string(@period_start)} - #{@as_string(@period_end)}"

  invoice_date: =>
    "#{@as_string(@date)}"

  resend: (data, event) =>
    $.ajax
      url: "/api/v1/organization/#{VM.org().name()}/invoice/resend"
      event: event
      type: 'POST'
      data: JSON.stringify
        id: @id

# TODO: strip out most of billing and move it to Plan and Card
CI.inner.Billing = class Billing extends CI.inner.Obj
  observables: =>
    stripeToken: null
    cardInfo: null
    invoices: []

    cancel_reasons: []
    cancel_notes: ""
    cancel_errors: null
    show_cancel_errors: false

    # old data
    oldPlan: null
    oldTotal: 0

    # metadata
    wizardStep: 1
    planFeatures: []

    # new data
    chosenPlan: null
    plans: []
    containers: 1
    containers_override: null
    special_price_p: null

    # org-plan data
    base_template_id: null
    org_name: null # org that is paying for the plan
    piggieback_orgs: []
    trial_end: null
    billing_name: null
    billing_email: null
    extra_billing_data: null
    account_balance: null # stripe credit

    # make it work
    current_org_name: null # organization that instantiated the billing class

    transfer_org_name: null

    # loaded (there has to be a better way)
    plans_loaded: false
    card_loaded: false
    invoices_loaded: false
    existing_plan_loaded: false
    stripe_loaded: false
    too_many_extensions: false
    current_containers: null

  constructor: ->
    super

    # This will get set to current_user.loadingOrganizations at load time
    @loadingOrganizations = false

    @loaded = @komp =>
      _.every ['plans', 'card', 'invoices', 'existing_plan', 'stripe'], (type) =>
        @["#{type}_loaded"].call()

    @savedCardNumber = @komp =>
      return "" unless @cardInfo()
      "************" + @cardInfo().last4

    @wizardCompleted = @komp =>
      @wizardStep() > 3

    @total = @komp =>
      @calculateCost(@chosenPlan(), parseInt(@containers()))

    @extra_containers = @komp =>
      if @chosenPlan()
        Math.max(0, @containers() - @chosenPlan().free_containers())

    @trial = @komp =>
      @chosenPlan() and @chosenPlan().type() is 'trial'

    @trial_over = @komp =>
      if @trial() && @trial_end()
        moment().diff(@trial_end()) > 0

    @trial_days = @komp =>
      if @trial() && @trial_end()
        moment(@trial_end()).diff(moment(), 'days') + 1

    @max_containers = @komp =>
      if @current_containers() < 10
        80
      else
        num = @current_containers() + 80
        num - num % 10 + 10

    @show_extend_trial_button = @komp =>
      !@too_many_extensions() && (@trial_over() or @trial_days() < 3)

    @pretty_trial_time = @komp =>
      if @trial() && @trial_end()
        days = moment(@trial_end()).diff(moment(), 'days')
        hours = moment(@trial_end()).diff(moment(), 'hours')
        if hours > 24
          "#{days + 1} days"
        else if hours > 1
          "#{hours} hours"
        else
          "#{moment(@trial_end()).diff(moment(), 'minutes')} minutes"



    @paid = @komp =>
      @chosenPlan() and @chosenPlan().type() isnt 'trial'

    @piggieback_plan_p = @komp =>
      @current_org_name() && @org_name() && @current_org_name() isnt @org_name()

    @can_edit_plan = @komp =>
      @paid() && !@piggieback_plan_p()

    @organization_plan_path = @komp =>
      CI.paths.org_settings(@org_name(), 'plan')

    @piggieback_plan_name = @komp =>
      if plan = _.first(_.filter @plans(), (p) => p.id is @base_template_id())
        plan.name

    @usable_containers = @komp =>
      free_containers = if @chosenPlan() then @chosenPlan().free_containers()
      Math.max @containers_override(), @containers(), free_containers

    # array of all organization logins that this user should see on the
    # select organizations page
    @all_orgs = ko.computed
      read: () =>
        user_orgs = VM.current_user().organizations_plus_user()

        _.chain(@piggieback_orgs().concat(_.pluck(user_orgs, 'login')))
          .sort()
          .uniq()
          .without(@org_name())
          .value()
      # VM won't be defined when we instantiate VM.billing() for outer
      deferEvaluation: true

    # Only orgs that the user is a member of
    @transferable_orgs = ko.computed
      read: () =>
        user_orgs = VM.current_user().organizations_plus_user()

        _.without(_.pluck(user_orgs, 'login'), @org_name())
      deferEvaluation: true

    @cancelFormErrorText = @komp =>
      # returns a string if the user hasn't filled out the cancel form correctly, else nil
      c = @cancel_reasons()
      if c.length is 0
        return "Please select at least one reason."
      if _.contains(c, "other") and not @cancel_notes()
        return "Please specify above."
      null

    @cancelTextareaAltText = @komp =>
      if _.contains(@cancel_reasons(), "other")
        "Would you mind elaborating some?"
      else
        "Have any other thoughts?"

  containers_option_text: (c) =>
    container_price = @chosenPlan().container_cost
    cost = @containerCost(@chosenPlan(), c)
    "#{c} containers ($#{cost})"

  containerCost: (plan, containers) ->
    c = Math.min(containers or 0, plan.max_containers())
    free_c = plan.free_containers()

    Math.max(0, (c - free_c) * plan.container_cost)

  calculateCost: (plan, containers) =>
    if plan
      plan.price + @containerCost(plan, containers)
    else
      0

  cancelUpdate: (data, event) =>
    $('#confirmForm').modal('hide') # TODO: eww
    @chosenPlan(@oldPlan())

  ajaxSetCard: (event, token, type) =>
    $.ajax
      type: type
      url: @apiURL("card")
      event: event
      data: JSON.stringify
        token: token
      success: (data) =>
        @cardInfo(data)

  stripeDefaults: () =>
    key: @stripeKey()
    name: "CircleCI"
    address: false
    email: VM.current_user().selected_email()

  updateCard: (data, event) =>
    vals =
      panelLabel: 'Update card',
      token: (token) =>
        @ajaxSetCard(event, token.id, "PUT")

    StripeCheckout.open($.extend @stripeDefaults(), vals)

  ajaxNewPlan: (plan, token, event) =>
    $.ajax
      url: @apiURL('plan')
      event: event
      type: 'POST'
      data: JSON.stringify
        token: token
        'base-template-id': plan.id # all new plans are p18
        'billing-email': @billing_email() || VM.current_user().selected_email()
        'billing-name': @billing_name() || @org_name()
        'containers' : plan.containers
      success: (data) =>
        CI.tracking.trackPayer(VM.current_user().login)
        @loadPlanData(data)
        @loadInvoices()
        VM.org().subpage('containers')

  ajaxUpdatePlan: (changed_attributes, event) =>
    $.ajax
      url: @apiURL('plan')
      event: event
      type: 'PUT'
      data: JSON.stringify(changed_attributes)
      success: (data) =>
        @loadPlanData(data)
        @loadInvoices()
        if VM.org().subpage() is 'plan'
          $('#confirmForm').modal('hide') # TODO: eww
          VM.org().subpage('containers')

  newPlan: (containers, event) =>
    # hard-coded single plan
    plan = new CI.inner.Plan
      price: 19
      container_cost: 50
      id: "p18"
      containers: containers
      max_containers: 1000

    cost = @calculateCost(plan, containers)
    description = "$#{cost}/month, includes #{containers} container" + if (containers > 1) then "s." else "."
    vals =
      panelLabel: 'Pay' # TODO: better label (?)
      price: 100 * cost
      description: description
      token: (token) =>
        @cardInfo(token.card)
        @ajaxNewPlan(plan, token, event)

    StripeCheckout.open(_.extend @stripeDefaults(), vals)

  ajaxCancelPlan: (_, event) =>
    console.log("ajaxCancelPlan")

    @show_cancel_errors(false)

    if @cancelFormErrorText()
      @show_cancel_errors(true)
      @cancelFormErrorText.subscribe (value) =>
        if not value?
          @show_cancel_errors(false)
      return

    $.ajax
      url: @apiURL('plan')
      type: 'DELETE'
      event: event
      data: JSON.stringify
        'cancel-reasons': @cancel_reasons()
        'cancel-notes': @cancel_notes()
      success: (data) =>
        @loadExistingPlans()
        VM.org().subpage('plan')

  updatePlan: (data, event) =>
    @ajaxUpdatePlan {"base-template-id": @chosenPlan().id}, event

  update_billing_info: (data, event) =>
    billing_data =
      'billing-email': @billing_email()
      'billing-name': @billing_name()
      'extra-billing-data': @extra_billing_data()
    @ajaxUpdatePlan billing_data, event

  saveContainers: (data, event) =>
    mixpanel.track("Save Containers")

    @ajaxUpdatePlan {containers: parseInt(@containers())}, event

  load: (hash="small") =>
    @loadPlans()
    @loadPlanFeatures()
    @loadExistingCard()
    @loadInvoices()
    @loadExistingPlans()
    @loadOrganizations()#
    @loadStripe()

  stripeKey: () =>
    switch renderContext.env
      when "production" then "pk_ZPBtv9wYtkUh6YwhwKRqL0ygAb0Q9"
      else 'pk_Np1Nz5bG0uEp7iYeiDIElOXBBTmtD'

  apiURL: (suffix) =>
    "/api/v1/organization/#{@current_org_name()}/#{suffix}"

  advanceWizard: =>
    @wizardStep(@wizardStep() + 1)

  closeWizard: =>
    @wizardStep(4)

  loadStripe: () =>
    $.getScript "https://js.stripe.com/v1/"
    $.getScript("https://checkout.stripe.com/v2/checkout.js")
      .success(() => @stripe_loaded(true))

  loadPlanData: (data) =>
    # update containers, extra_orgs, and extra invoice info
    @updateObservables(data)

    @oldTotal(data.amount / 100)
    @chosenPlan(new CI.inner.Plan(data.template_properties, @)) if data.template_properties
    @special_price_p(@oldTotal() <  @total())
    @current_containers(@containers())

  loadExistingPlans: () =>
    $.getJSON @apiURL('plan'), (data) =>
      @loadPlanData data if data
      @existing_plan_loaded(true)

  loadOrganizations: () =>
    @loadingOrganizations = VM.current_user.loadingOrganizations
    VM.current_user().loadOrganizations()

  saveOrganizations: (data, event) =>
    mixpanel.track("Save Organizations")
    @ajaxUpdatePlan {'piggieback-orgs': @piggieback_orgs()}, event

  extendTrial: (data, event) =>
    $.ajax
      type: 'POST'
      url: @apiURL('extend-trial')
      event: event
      success: (data) =>
        @loadPlanData(data)
        mixpanel.track("Extend trial")

  transferPlan: (data, event) =>
    $.ajax
      type: 'PUT'
      url: @apiURL('transfer-plan')
      event: event
      data: JSON.stringify
        'org-name': @transfer_org_name()
      success: (data) =>
        VM.org().subpage('plan')
        @load()

  loadExistingCard: () =>
    $.getJSON @apiURL('card'), (card) =>
      @cardInfo card
      @card_loaded(true)

  loadInvoices: () =>
    $.getJSON @apiURL('invoices'), (invoices) =>
      if invoices
        @invoices(new Invoice(i) for i in invoices)
      @invoices_loaded(true)


  loadPlans: () =>
    $.getJSON '/api/v1/plans', (data) =>
      @plans((new CI.inner.Plan(d, @) for d in data))
      @plans_loaded(true)

  loadPlanFeatures: () =>
    @planFeatures(CI.content.pricing_features)

  transfer_plan_button_text: () =>
    str = "Transfer plan"
    if @transfer_org_name()
      str += " to #{@transfer_org_name()}"
    str

  popover_options: (extra) =>
    options =
      html: true
      trigger: 'hover'
      delay: 0
      animation: false
      placement: 'bottom'
     # this will break when we change bootstraps! take the new template from bootstrap.js
      template: '<div class="popover billing-popover"><div class="popover-inner"><h3 class="popover-title"></h3><div class="popover-content"></div></div></div>'

    for k, v of extra
      options[k] = v

    options


CI.inner.Billing.cancelReasons =
 ## the values (but not text) need to match the SF DB Api, so don't change lightly
  [{value: "project-ended", text: "Project Ended"},
   {value: "slow-performance", text: "Slow Performance"},
   {value: "unreliable-performance", text: "Unreliable Performance"},
   {value: "too-expensive", text: "Too Expensive"},
   {value: "didnt-work", text: "Couldn't Make it Work"},
   {value: "missing-feature", text: "Missing Feature"},
   {value: "poor-support", text: "Poor Support"},
   {value: "other", text: "Other"}]
