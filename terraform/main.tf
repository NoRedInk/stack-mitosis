terraform {
  required_version = "~> 0.13.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 3.0"
    }
  }
}

locals {
  source = {
    port                    = 5430
    backup_retention_period = 1
    backup_window           = "05:30-06:20"
    maintenance_window      = "tue:07:02-tue:08:00"
  }
  target = {
    port               = 5431
    backup_retention_period = 1
    backup_window           = "05:20-06:20"
    maintenance_window = "mon:07:30-mon:08:00"
  }

  mysql       = { engine = "mysql" }
  postgres    = { engine = "postgres" }
  vpc_a = { db_subnet_group_name = null }
  vpc_b     = { db_subnet_group_name = aws_db_subnet_group.main.name }

  sources = {
    mitosis-mysql-src                     = merge(local.source, local.mysql, local.vpc_a),
    mitosis-postgres-src                  = merge(local.source, local.postgres, local.vpc_a),
  }
  targets = {
    mitosis-mysql-target-same-vpc         = merge(local.target, local.mysql, local.vpc_a),
    mitosis-mysql-target-different-vpc    = merge(local.target, local.mysql, local.vpc_b),
    mitosis-postgres-target-same-vpc      = merge(local.target, local.postgres, local.vpc_a),
    mitosis-postgres-target-different-vpc = merge(local.target, local.postgres, local.vpc_b),
  }
  dbs = merge(local.sources, local.targets)
}

# Configure the AWS Provider
provider "aws" {
  # Or whatever region you want
  region = "us-west-2"
}

# Current region
data "aws_region" "current" {}

# Create a VPC
resource "aws_vpc" "main" {
  cidr_block = "10.0.0.0/16"
  tags = {
    Name    = "stack-mitosis"
    Service = "Mitosis"
    Env     = "test"
  }
}

# Create two subnets so we can create a subnet group
resource "aws_subnet" "a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.0.0/24"
  availability_zone = "${data.aws_region.current.name}a"

  tags = {
    Name    = "Mitosis A"
    Service = "Mitosis"
    Env     = "test"
  }
}

resource "aws_subnet" "b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "${data.aws_region.current.name}b"

  tags = {
    Name    = "Mitosis B"
    Service = "Mitosis"
    Env     = "test"
  }
}

# Subnet group for our databases
resource "aws_db_subnet_group" "main" {
  name       = "mitosis-test"
  subnet_ids = [aws_subnet.a.id, aws_subnet.b.id]

  tags = {
    Name    = "Mitosis"
    Service = "Mitosis"
    Env     = "test"
  }
}

# Random password generator for the required field `password` on the DBs
resource "random_password" "password" {
  length           = 16
  special          = false
}

# Create our databases
resource "aws_db_instance" "main" {
  for_each            = local.dbs
  allocated_storage   = 10
  instance_class      = "db.t3.micro"
  username            = "foo"
  skip_final_snapshot = true

  password = random_password.password.result

  identifier              = each.key
  engine                  = each.value.engine
  db_subnet_group_name    = each.value.db_subnet_group_name
  backup_retention_period = each.value.backup_retention_period
  backup_window           = each.value.backup_window
  maintenance_window      = each.value.maintenance_window
  port                    = each.value.port

  tags = {
    Service = "Mitosis"
    Env     = "test"
  }
}

# And their replicas
resource "aws_db_instance" "replica" {
  for_each             = aws_db_instance.main
  identifier           = "${each.value.id}-replica"
  replicate_source_db  = each.value.id
  engine               = each.value.engine
  instance_class       = "db.t3.micro"
  skip_final_snapshot  = true

  tags = {
    Service = "Mitosis"
    Env     = "test"
  }
}
