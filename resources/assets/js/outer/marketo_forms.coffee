CI.outer.MarketoForms = class MarketoForms

  constructor: (@form, @formid) ->
    @FirstName = ko.observable()
    @LastName = ko.observable()
    @Email = ko.observable()
    @Company = ko.observable()
    @repo_solution__c = ko.observable()
    @DockerUse = ko.observable()
    @munchkinId = "894-NPA-635"
    @_mkto_trk = $.cookie('_mkto_trk')
    @notice = ko.observable()
    @message = ko.observable()
    @other_field = ko.observable()
    @display_other = ko.computed =>
      @repo_solution__c() == 'other'
    @other_input = ko.observable()

  submitShopifyStoryForm: (data, event) =>
    if not (@Email())
      @notice
        type: 'error'
        message: 'Email is required.'

    else
      $.ajax
        url: "http://app-abm.marketo.com/index.php/leadCapture/save2"
        type: "POST"
        event: event
        data:
          FirstName: @FirstName()
          Email: @Email()
          Company: @Company()
          munchkinId: @munchkinId
          formid: @formid
          _mkt_trk: @_mkt_trk
          formVid: @formid
        contentType: "application/x-www-form-urlencoded; charset=UTF-8"

        success: () =>
          @FirstName(null)
          @LastName(null)
          @Email(null)
          @Company(null)
          @message(null)
          @repo_solution__c(null)
          @notice
            type: 'success'
            message: 'Thanks! We will be in touch soon.'
        error: (error) =>
          @notice
            type: 'error'
            message: 'Network error! Please reach out at sayhi@circleci.com. Thanks!'

  submitDockerForm: (data, event) =>
      if not (@Email())
        @notice
          type: 'error'
          message: 'Email is required.'

      else if mktoMunchkin?
        $.ajax
          url: "http://app-abm.marketo.com/index.php/leadCapture/save2"
          type: "POST"
          event: event
          data:
            Email: @Email()
            docker_use__c: @DockerUse()
            munchkinId: @munchkinId
            formid: @formid
            _mkt_trk: @_mkt_trk
            formVid: @formid
          contentType: "application/x-www-form-urlencoded; charset=UTF-8"

          success: () =>
            @Email(null)
            @DockerUse(null)
            @notice
              type: 'success'
              message: 'Thanks! We will be in touch soon.'
          error: (error) =>
            @notice
              type: 'error'
              message: 'Network error! Please reach out at sayhi@circleci.com. Thanks!'
      else
        $.ajax
          url: "/about/contact"
          type: "POST"
          event: event
          data:
            name: 'interested in Docker'
            email: @Email()
            message: @DockerUse()

          contentType: "application/x-www-form-urlencoded; charset=UTF-8"
          success: (data) =>
            # clear inputs
            @Email(null)
            @DockerUse(null)
            @notice
              type: 'success'
              message: 'Thanks! We will be in touch soon.'
                
            
            
