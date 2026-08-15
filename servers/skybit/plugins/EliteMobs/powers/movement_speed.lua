return {
  api_version = 1,
  on_spawn = function(context)
    context.scheduler:run_after(1, function()
      if context.boss:is_alive() then
        context.boss:apply_potion_effect("SPEED", 100000, 1)
      end
    end)
  end
}
