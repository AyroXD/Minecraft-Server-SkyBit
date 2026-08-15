return {
  api_version = 1,
  on_spawn = function(context)
    context.boss:apply_potion_effect("JUMP_BOOST", -1, 3)
  end
}
