CI.instrumentation =
  response_data: ko.observableArray([])
  display: ko.observable(true)
  expanded: ko.observable(false)

  recordResponse: (data) ->
    @response_data.push(data)

  clearResponses: () ->
    @response_data([])

  toggleDisplay: () ->
    @display(not @display())

  toggleExpanded: () ->
    @expanded(not @expanded())

  maybeRecordResponse: (xhr, options) ->
    if xhr.getResponseHeader("X-CircleCI-Latency")
      @recordResponse
        url: options.url
        type: options.type
        client_latency: (new Date()) - options.__instrumentation_start_time
        circle_latency: parseInt(xhr.getResponseHeader("X-CircleCI-Latency"))
        query_count: parseInt(xhr.getResponseHeader("X-CircleCI-Query-Count"))
        query_latency: parseInt(xhr.getResponseHeader("X-CircleCI-Query-Latency"))

  init: () ->
    $(document).ajaxSend (ev, xhr, options) =>
      options.__instrumentation_start_time = new Date()

    $(document).ajaxComplete (ev, xhr, options) =>
      @maybeRecordResponse(xhr, options)
