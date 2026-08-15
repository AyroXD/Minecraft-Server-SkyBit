local fireball_speed = 0.5

return {
  api_version = 1,
  on_boss_target_changed = function(context)
    if not context.boss.is_monster then
      return
    end

    context.boss:start_tracking_fireball_system(fireball_speed)
  end
}
