return {
  api_version = 1,
  on_player_damaged_by_boss = function(context)
    if context.player == nil then
      return
    end
    context.player:set_fire_ticks(60)
  end
}
