return {
  api_version = 1,
  on_death = function(context)
    context.world:place_temporary_block_at_location(context.boss:get_location(), "BONE_BLOCK", 2400, true)
  end
}
