# Test environment Terraform code

This terraform code creates a test environment for all invariants of `stack-mitosis`:

- MySQL
  - Same VPC
  - Different VPC
- Postgres
  - Same VPC
  - Different VPC

## How to use

    $ terraform apply

Wait some 15 minutes.

Terraform output:

    Apply complete! Resources: 17 added, 0 changed, 0 destroyed.

    Outputs:

    test_commands = clj -m stack-mitosis.cli --source mitosis-mysql-src --target mitosis-mysql-target-different-vpc
    clj -m stack-mitosis.cli --source mitosis-mysql-src --target mitosis-mysql-target-same-vpc
    clj -m stack-mitosis.cli --source mitosis-postgres-src --target mitosis-postgres-target-different-vpc
    clj -m stack-mitosis.cli --source mitosis-postgres-src --target mitosis-postgres-target-same-vpc

Run the test commands in the terraform output to validate stack-mitosis against all pairs of source and target instances.