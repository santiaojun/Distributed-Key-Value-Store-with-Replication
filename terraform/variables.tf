variable "project_name" {
  description = "Project name prefix for AWS resources"
  type        = string
  default     = "cs6650-a4"
}

variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "us-east-1"
}

variable "ami_id" {
  description = "AMI used for EC2 nodes"
  type        = string
}

variable "key_name" {
  description = "EC2 key pair name"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type for KV nodes and the load tester"
  type        = string
  default     = "t3.micro"
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "subnet_ids" {
  description = "Optional explicit subnet IDs for EC2 and ALB. Leave empty to resolve subnets by subnet_names."
  type        = list(string)
  default     = []
}

variable "subnet_names" {
  description = "Subnet Name tag values used when subnet_ids is empty"
  type        = list(string)
  default     = []
}

variable "allowed_cidr_block" {
  description = "CIDR range allowed to access nodes"
  type        = string
  default     = "0.0.0.0/0"
}
