CI.terminal =
  ansiToHtmlConverter: (defaultColor, defaultBackgroundColor, state={}) ->
    default_state =
      trailing_raw: ""
      trailing_out: ""
      color: defaultColor
      bgcolor: defaultBackgroundColor
      italic: false
      bold: false

    initial_state = _.extend(default_state, state)

    trailing_raw = initial_state.trailing_raw
    trailing_out = initial_state.trailing_out

    style =
      color: initial_state.color
      italic: initial_state.italic
      bold: initial_state.bold
      bgcolor: initial_state.bgcolor

      reset: () ->
        @color = defaultColor
        @bgcolor = defaultBackgroundColor
        @italic = false
        @bold = false

      add: (n) ->
        switch parseInt(n)
          when 0 then @reset()
          when 1 then @bold = true
          when 3 then @italic = true
          when 22 then @bold = false
          when 23 then @italic = false
          when 30 then @color = "white" ## actually black, but we use a black background
          when 31 then @color = "red"
          when 32 then @color = "green"
          when 33 then @color = "yellow"
          when 34 then @color = "blue"
          when 35 then @color = "magenta"
          when 36 then @color = "cyan"
          when 37 then @color = "white"
          when 39 then @color = defaultColor
          when 40 then @bgcolor = "white"
          when 41 then @bgcolor = "red"
          when 42 then @bgcolor = "green"
          when 43 then @bgcolor = "yellow"
          when 44 then @bgcolor = "blue"
          when 45 then @bgcolor = "magenta"
          when 46 then @bgcolor = "cyan"
          when 47 then @bgcolor = "white"
          when 49 then @bgcolor = defaultBackgroundColor
          when 90 then @color = "brwhite"
          when 91 then @color = "brred"
          when 92 then @color = "brgreen"
          when 93 then @color = "bryellow"
          when 94 then @color = "brblue"
          when 95 then @color = "brmagenta"
          when 96 then @color = "brcyan"
          when 99 then @color = defaultColor

      classes: () ->
        classes = []
        if @bold and not @color.match(/^br/)
          classes.push("br#{@color}")
        else if @color != defaultColor
          classes.push("#{@color}")
        if @italic
          classes.push("italic")
        if @bgcolor != defaultBackgroundColor
          classes.push("bg-#{@bgcolor}")

        classes


      applyTo: (content) ->
        if content
          classes = @classes()
          if classes.length
            "<span class='#{classes.join(' ')}'>#{content}</span>"
          else
            content
        else
          ""
      currentState: () ->
        color: @color
        italic: @italic
        bold: @bold


    currentState: () ->
      _.extend
        trailing_raw: trailing_raw
        trailing_out: trailing_out
      , style.currentState()


    wrapDefaultColor: (content) ->
      if (not content?) or (content is "")
        ""
      else
        "<span class='#{defaultColor}'>#{content}</span>"

    get_trailing: () ->
      @wrapDefaultColor(trailing_out)

    append: (str) ->
      # http://en.wikipedia.org/wiki/ANSI_escape_code
      start   = 0
      current = trailing_raw + str
      output  = ""

      trailing_raw = ""
      trailing_out = ""

      # loop over lines. ^[0G is treated as equivalent to \r, and acts as a line separator.
      while current.length and ((line_end = current.search(/\u001B\[0G|\r|\n|$/)) != -1)
        # find end of the line terminator
        terminator_end = line_end
        while true
          if current.lastIndexOf("\u001B\[0G", terminator_end) == terminator_end
            terminator_end += 4
          else if current.lastIndexOf("\r", terminator_end) == terminator_end
            terminator_end += 1
          else if current.lastIndexOf("\n", terminator_end) == terminator_end
            terminator_end += 1
          else
            break

        terminator = current.slice(line_end, terminator_end)
        input_line = current.slice(0, line_end + terminator.length)
        original_input_line = input_line
        output_line = ""

        # loop over escape sequences within the line
        while (escape_start = input_line.indexOf('\u001B[')) != -1
          # append everything up to the start of the escape sequence to the output
          output_line += style.applyTo(input_line.slice(0, escape_start))

          # find the end of the escape sequence -- a single letter
          rest = input_line.slice(escape_start + 2)
          escape_end = rest.search(/[A-Za-z]/)

          # point "input_line" at first character after the end of the escape sequence
          input_line = rest.slice(escape_end + 1)

          # only actually deal with 'm' escapes
          if rest.charAt(escape_end) == 'm'
            escape_sequence = rest.slice(0, escape_end)
            if escape_sequence == ''
              # \esc[m is equivalent to \esc[0m
              style.reset()
            else
              escape_codes = escape_sequence.split(';')
              style.add esc for esc in escape_codes

        current = current.slice(line_end + terminator.length)
        output_line += style.applyTo(input_line)

        if not current.length
          ## the last line is "trailing"
          trailing_raw = original_input_line
          trailing_out = output_line
        else
          # don't write the output line if it ends with a carriage return or ^[0G, for
          # primitive terminal animations...
          if terminator.search(/^(\u001B\[0G|\r)+$/) == -1
            output += output_line

      @wrapDefaultColor(output)

  ansiToHtml: (str) ->
    # convenience function for testing
    converter = @ansiToHtmlConverter("brblue", "brblack")
    converter.append(str) + converter.get_trailing()
