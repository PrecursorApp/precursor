CI.inner.Plan = class Plan extends CI.inner.Obj
  observables: =>
    free_containers: 1
    max_containers: 1
    billing: null
    max_parallelism: 1
    type: 'trial'

  constructor: (json, billing) ->
    super json

    @billing(billing)

    @container_options = ko.observableArray([@free_containers()..@max_containers()])

    @allowsParallelism = @komp =>
      @max_parallelism() > 1

    @projectsTitle = @komp =>
      "#{@projects} project" + (if @projects == 1 then "" else "s")

    @minParallelismDescription = @komp =>
      "#{@min_parallelism}x"

    @maxParallelismDescription = @komp =>
      "up to #{@max_parallelism()}x"

    @freeContainersDescription = @komp =>
      "#{@free_containers()} container" + (if @free_containers() == 1 then "" else "s")

    @containerCostDescription = @komp =>
      if @container_cost
        "$#{@container_cost} / container"
      else
        "Contact us"

    @pricingDescription = @komp =>
      if @billing() and @billing().chosenPlan()? and @.id == @billing().chosenPlan().id
        "Your current plan"
      else
        if not @price?
          "Contact us for pricing"
        else
          if @billing() and @billing().can_edit_plan()
            "Switch plan $#{@price}/mo"
          else
            "Sign up now for $#{@price}/mo"

    @outerPricingDescription = @komp =>
      if not @price?
        "Contact us for pricing"
      else
        "$#{@price} / month"

    @enterprise_p = @komp =>
      @name is "Enterprise"

  featureAvailableOuter: (feature) =>
    result = not feature.name? or feature.name in @features

  featureAvailable: (feature) =>
    result =
      tick: not feature.name? or feature.name in @features
    if feature.name?
      result[feature.name] = true
    result
