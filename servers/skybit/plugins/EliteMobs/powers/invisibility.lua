return {
  api_version = 1,
  on_spawn = function(context)
    context.boss:apply_potion_effect("invisibility", 2147483647, 0)
  end
}
