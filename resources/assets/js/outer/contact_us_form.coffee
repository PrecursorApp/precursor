CI.outer.ContactUsForm = class ContactUsForm

  constructor: (@form, @opts={}) ->
    @notice = ko.observable()
    @name = ko.observable()
    @email = ko.observable()
    @message = ko.observable()

  submitAjaxForm: (data, event) =>
    # don't let notices change the width
    # There must be a better way to do this...
    $(@form).css 'width', $(@form).width()

    if not (@name() && @email())
      @notice
        type: 'error'
        message: 'Name, email, and message are all required.'

    else
      $.ajax
        url: "/about/contact"
        type: "POST"
        event: event
        data:
          name: @name()
          email: @email()
          message: @message()
          enterprise: @opts.enterprise

        contentType: "application/x-www-form-urlencoded; charset=UTF-8"
        success: (data) =>
          if data && data.message
            # show the message from the server
            @notice(data)

          # clear inputs
          @name(null)
          @email(null)
          @message(null)
