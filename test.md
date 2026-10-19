# AWS Test Run Checklist

Use `us-east-1` for this assignment. Do not manually create EC2 instances or
load balancers in the AWS console. Use the console only to collect required IDs
and inspect resources; create and destroy infrastructure with Terraform.

## 1. Confirm Region

In the AWS console, set the region selector in the top bar to:

```text
United States (N. Virginia) / us-east-1
```

## 2. Create EC2 Key Pair

Open:

```text
AWS Console -> EC2 -> Network & Security -> Key Pairs -> Create key pair
```

Use:

```text
Name: cs6650-a4-key
Key pair type: RSA
Private key file format: .pem
```

Download and keep the `.pem` file. It is required for SSH access.

## 3. Find AMI ID

Open:

```text
AWS Console -> EC2 -> AMI Catalog
```

Search for:

```text
Amazon Linux 2023 AMI
```

Choose the x86_64 AMI in `us-east-1` and copy its AMI ID, for example:

```text
ami-098e39bafa7e7303d
```

## 4. Find VPC and Subnets

Open:

```text
AWS Console -> VPC -> Your VPCs
```

Copy the target VPC ID:

```text
vpc-0ded341ff3977a27f
```

Then open:

```text
AWS Console -> VPC -> Subnets
```

Use these two public subnet Name values shown in the VPC resource map:

```text
cs6650-a3-public-1
cs6650-a3-public-2
```

Terraform will resolve their subnet IDs from these names. If lookup by name
fails, open each subnet and copy its explicit `subnet-...` ID into
`subnet_ids` instead.

## 5. Create Terraform Variables

From the repository root:

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`:

```hcl
aws_region         = "us-east-1"
project_name       = "cs6650-a4"
ami_id             = "ami-098e39bafa7e7303d"
key_name           = "cs6650-a4-key"
instance_type      = "t3.micro"
vpc_id             = "vpc-0ded341ff3977a27f"
subnet_names       = ["cs6650-a3-public-1", "cs6650-a3-public-2"]
subnet_ids         = []
allowed_cidr_block = "YOUR_PUBLIC_IP/32"
```

Use your own public IP for `allowed_cidr_block`. Avoid using `0.0.0.0/0`
unless it is temporary and immediately removed.

## 6. Create AWS Infrastructure

From `terraform/`:

```bash
terraform init
terraform plan
terraform apply
```

After `terraform apply`, record these outputs:

```text
leader_url
kv_node_urls
kv_node_public_ips
kv_node_private_ips
load_tester_public_ip
leaderless_alb_url
```

## 7. Verify Resources in AWS

Open:

```text
AWS Console -> EC2 -> Instances
```

Confirm there are 6 running instances:

```text
cs6650-a4-kv-node-1
cs6650-a4-kv-node-2
cs6650-a4-kv-node-3
cs6650-a4-kv-node-4
cs6650-a4-kv-node-5
cs6650-a4-load-tester
```

Wait for:

```text
Instance state: Running
Status check: 2/2 checks passed
```

Then open:

```text
AWS Console -> EC2 -> Load Balancing -> Load Balancers
```

Confirm:

```text
cs6650-a4-leaderless-alb
```

Also check:

```text
AWS Console -> EC2 -> Load Balancing -> Target Groups
```

Confirm the target group contains all 5 KV nodes.

## 8. Deploy Application Services

Use the deployment script from the repository root:

```bash
MODE=leader-follower WRITE_QUORUM_SIZE=5 READ_QUORUM_SIZE=1 ./scripts/deploy-aws.sh
```

The script builds the jars, installs Java 17 on the EC2 instances if needed,
copies the KV service to all five KV nodes, starts it with systemd, and copies
the load tester jar plus a runnable helper script to the load tester instance.

Leader-Follower modes:

```bash
MODE=leader-follower WRITE_QUORUM_SIZE=5 READ_QUORUM_SIZE=1 ./scripts/deploy-aws.sh
MODE=leader-follower WRITE_QUORUM_SIZE=3 READ_QUORUM_SIZE=3 ./scripts/deploy-aws.sh
MODE=leader-follower WRITE_QUORUM_SIZE=1 READ_QUORUM_SIZE=5 ./scripts/deploy-aws.sh
```

Leaderless mode:

```bash
MODE=leaderless ./scripts/deploy-aws.sh
```

After deployment, verify:

```bash
curl "http://LEADER_PUBLIC_IP:8080/local_read?key=healthcheck"
ssh -i cs6650-a4-key.pem ec2-user@LOAD_TESTER_PUBLIC_IP
```

On the load tester instance, run one ratio with:

```bash
WRITE_RATIO=0.01 MODE=leader-follower /opt/cs6650-a4/run-load-test.sh
```

To run the full AWS matrix and fetch result CSVs into
`load-tester/results/aws-final/`, run from the repository root:

```bash
./scripts/run-aws-experiments.sh
```

For a short smoke test of the runner:

```bash
CONFIGS=w5r1 RATIOS=0.01 THREADS=2 DURATION_SECONDS=8 ./scripts/run-aws-experiments.sh
```

## 9. Run Final Load Tests

Run four write ratios for each database configuration:

```text
1%, 10%, 50%, 90%
```

Leader-Follower configurations:

```text
W=5/R=1
W=3/R=3
W=1/R=5
```

Leaderless configuration:

```text
W=5/R=1
```

Use 600 seconds for final runs:

```bash
--duration 600
```

Store AWS results separately from local results:

```text
load-tester/results/aws-final/
```

Regenerate plots with:

```bash
cd load-tester
python3 -m pip install -r requirements.txt
RESULTS_DIR=results/aws-final python3 plot.py
```

## 10. Destroy AWS Resources

When the experiment is done for the day:

```bash
cd terraform
terraform destroy
```

Then verify in AWS:

```text
EC2 -> Instances
EC2 -> Load Balancers
EC2 -> Target Groups
EC2 -> Security Groups
```

Confirm that assignment resources are gone or stopped so they do not continue
charging the account.
