return {
  api_version = 1,
  on_player_damaged_by_boss = function(context)
    if context.player == nil then
      return
    end

    local pull = context.vectors.get_vector_between_locations(
      context.player:get_location(),
      context.boss:get_location()
    )
    pull = context.vectors.normalize_vector(pull)
    context.player:set_velocity_vector(pull)
  end
}
