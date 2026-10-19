terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  common_tags = {
    Project = var.project_name
  }
  selected_subnet_ids = length(var.subnet_ids) > 0 ? var.subnet_ids : data.aws_subnets.selected[0].ids
}

data "aws_subnets" "selected" {
  count = length(var.subnet_ids) == 0 ? 1 : 0

  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }

  filter {
    name   = "tag:Name"
    values = var.subnet_names
  }
}

resource "aws_security_group" "kv_nodes_sg" {
  name        = "${var.project_name}-kv-nodes-sg"
  description = "Security group for KV nodes"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-kv-nodes-sg"
  })
}

resource "aws_security_group" "load_tester_sg" {
  name        = "${var.project_name}-load-tester-sg"
  description = "Security group for the load tester instance"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-load-tester-sg"
  })
}

resource "aws_security_group" "alb_sg" {
  name        = "${var.project_name}-leaderless-alb-sg"
  description = "Security group for the leaderless application load balancer"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-leaderless-alb-sg"
  })
}

resource "aws_security_group_rule" "kv_http_from_allowed_cidr" {
  type              = "ingress"
  security_group_id = aws_security_group.kv_nodes_sg.id
  from_port         = 8080
  to_port           = 8080
  protocol          = "tcp"
  cidr_blocks       = [var.allowed_cidr_block]
  description       = "Allow direct KV HTTP access from approved client CIDR"
}

resource "aws_security_group_rule" "kv_http_from_peer_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.kv_nodes_sg.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.kv_nodes_sg.id
  description              = "Allow node-to-node replication traffic"
}

resource "aws_security_group_rule" "kv_http_from_load_tester" {
  type                     = "ingress"
  security_group_id        = aws_security_group.kv_nodes_sg.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.load_tester_sg.id
  description              = "Allow load tester traffic to KV nodes"
}

resource "aws_security_group_rule" "kv_http_from_alb" {
  type                     = "ingress"
  security_group_id        = aws_security_group.kv_nodes_sg.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.alb_sg.id
  description              = "Allow leaderless ALB traffic to KV nodes"
}

resource "aws_security_group_rule" "kv_ssh_from_allowed_cidr" {
  type              = "ingress"
  security_group_id = aws_security_group.kv_nodes_sg.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.allowed_cidr_block]
  description       = "Allow SSH to KV nodes from approved client CIDR"
}

resource "aws_security_group_rule" "load_tester_ssh_from_allowed_cidr" {
  type              = "ingress"
  security_group_id = aws_security_group.load_tester_sg.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.allowed_cidr_block]
  description       = "Allow SSH to load tester from approved client CIDR"
}

resource "aws_security_group_rule" "alb_http_from_allowed_cidr" {
  type              = "ingress"
  security_group_id = aws_security_group.alb_sg.id
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = [var.allowed_cidr_block]
  description       = "Allow HTTP access to leaderless ALB from approved client CIDR"
}

resource "aws_security_group_rule" "alb_http_from_load_tester" {
  type                     = "ingress"
  security_group_id        = aws_security_group.alb_sg.id
  from_port                = 80
  to_port                  = 80
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.load_tester_sg.id
  description              = "Allow load tester traffic to leaderless ALB"
}

resource "aws_instance" "kv_nodes" {
  count                  = 5
  ami                    = var.ami_id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = element(local.selected_subnet_ids, count.index % length(local.selected_subnet_ids))
  vpc_security_group_ids = [aws_security_group.kv_nodes_sg.id]

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-kv-node-${count.index + 1}"
    Role = "kv-node"
    Node = tostring(count.index + 1)
  })
}

resource "aws_instance" "load_tester" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = element(local.selected_subnet_ids, 0)
  vpc_security_group_ids = [aws_security_group.load_tester_sg.id]

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-load-tester"
    Role = "load-tester"
  })
}

resource "aws_lb" "leaderless_alb" {
  name               = "${var.project_name}-leaderless-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb_sg.id]
  subnets            = local.selected_subnet_ids

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-leaderless-alb"
  })
}

resource "aws_lb_target_group" "leaderless_target_group" {
  name        = "${var.project_name}-leaderless-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = var.vpc_id

  health_check {
    path                = "/local_read?key=healthcheck"
    matcher             = "200,404"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 2
    timeout             = 5
  }

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-leaderless-tg"
  })
}

resource "aws_lb_target_group_attachment" "leaderless_targets" {
  count            = 5
  target_group_arn = aws_lb_target_group.leaderless_target_group.arn
  target_id        = aws_instance.kv_nodes[count.index].id
  port             = 8080
}

resource "aws_lb_listener" "leaderless_http" {
  load_balancer_arn = aws_lb.leaderless_alb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.leaderless_target_group.arn
  }
}
