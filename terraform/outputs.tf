locals {
  commands = flatten([for src in keys(local.sources): 
    [for target in keys(local.targets): 
      "clj -m stack-mitosis.cli --source ${src} --target ${target}"
      if aws_db_instance.main[src].engine == aws_db_instance.main[target].engine
    ]
  ])
}
output "test_commands" {
  value = join("\n",local.commands)
}
