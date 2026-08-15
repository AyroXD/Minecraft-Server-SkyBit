return {
  api_version = 1,
  on_boss_damaged = function(context)
    if context.event.damage_cause == nil then
      return
    end
    context.boss:handle_spirit_walk_damage(context.event.damage_cause)
  end
}
