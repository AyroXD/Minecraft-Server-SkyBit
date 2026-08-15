return {
  api_version = 1,
  on_player_damaged_by_boss = function(context)
    if context.player == nil then
      return
    end
    context.player:apply_potion_effect("nausea", 200, 0)
  end
}
