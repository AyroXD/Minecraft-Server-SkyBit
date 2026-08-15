return {
  api_version = 1,
  on_death = function(context)
    context.world:generate_player_loot(1)
  end
}
